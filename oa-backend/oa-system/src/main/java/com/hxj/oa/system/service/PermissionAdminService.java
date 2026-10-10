package com.hxj.oa.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hxj.oa.common.exception.BizException;
import com.hxj.oa.common.security.UserContext;
import com.hxj.oa.system.auth.AuthSnapshotCache;
import com.hxj.oa.system.dto.PermissionSaveReq;
import com.hxj.oa.system.entity.SysPermission;
import com.hxj.oa.system.mapper.RolePermissionMapper;
import com.hxj.oa.system.mapper.SysPermissionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

/**
 * 权限点管理（写侧）：新增 / 编辑 / 删除权限点 —— 客户在界面上自助扩展功能权限的入口。
 *
 * <p><b>为什么 code 创建后不可改</b>：code 是三方契约——角色绑定表（role_permission.perm_code）、
 * 用户 JWT 的 permCodes、后端 {@code @RequirePerm} 注解入参。改 code 等于让所有既有授权
 * 静默失效（绑定行的 perm_code 成了死串），且无法从界面上察觉。
 *
 * <p><b>为什么删除前必须无绑定</b>：被角色绑定的权限点删掉后，绑定行 perm_code 悬空，
 * 该角色的成员 JWT 里仍带着这个 code——权限"看起来还在"，实际对应的目录行已消失，
 * 属于典型的「绑了不生效」歧义态。先解绑再删，让每一步都有明确的语义。
 *
 * <p><b>权限快照</b>：菜单可见性（loadMenus 查 sys_permission × role_permission）与
 * permCodes 都来自登录快照，任何写操作后必须失效缓存，与 {@link RoleAdminService} 同规。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionAdminService {

    private final SysPermissionMapper permissionMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final AuthSnapshotCache snapshotCache;

    /* ------------------------------------------------------------------ 写 */

    @Transactional(rollbackFor = Exception.class)
    public SysPermission create(PermissionSaveReq req) {
        String code = req.getCode().trim();
        assertCodeFree(code, null);
        SysPermission parent = requireParent(req.getParentCode(), null);
        assertTypeShape(req.getPermType(), parent);

        SysPermission p = new SysPermission();
        p.setCode(code);
        p.setName(req.getName().trim());
        p.setPermType(req.getPermType());
        p.setParentCode(parent == null ? null : parent.getCode());
        p.setSortNo(req.getSortNo() == null ? nextSortNo(parent) : req.getSortNo());
        // sys_permission 无 created_by/updated_by 列（操作人靠 trace 日志留痕），不设审计人字段
        permissionMapper.insert(p);
        log.info("新增权限点 id={} code={} type={} parent={} 操作人={}",
                p.getId(), code, p.getPermType(), p.getParentCode(), UserContext.currentUserId());
        snapshotCache.invalidateAfterCommit();
        return p;
    }

    @Transactional(rollbackFor = Exception.class)
    public SysPermission update(Long id, PermissionSaveReq req) {
        SysPermission exist = requirePerm(id);
        SysPermission parent = requireParent(req.getParentCode(), exist);
        assertTypeShape(req.getPermType(), parent);
        if (!Objects.equals(req.getCode(), exist.getCode())) {
            throw BizException.of("权限编码创建后不可修改（%s 已被角色绑定与后端鉴权注解引用，改名会静默废掉既有授权）",
                    exist.getCode());
        }

        SysPermission upd = new SysPermission();
        upd.setId(id);
        upd.setName(req.getName().trim());
        upd.setPermType(req.getPermType());
        upd.setParentCode(parent == null ? null : parent.getCode());
        if (req.getSortNo() != null) {
            upd.setSortNo(req.getSortNo());
        }
        permissionMapper.updateById(upd);
        log.info("编辑权限点 id={} code={} parent={} type={} 操作人={}",
                id, exist.getCode(), upd.getParentCode(), upd.getPermType(), UserContext.currentUserId());
        snapshotCache.invalidateAfterCommit();
        return permissionMapper.selectById(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        SysPermission p = requirePerm(id);
        long children = permissionMapper.selectCount(Wrappers.<SysPermission>lambdaQuery()
                .eq(SysPermission::getParentCode, p.getCode()));
        if (children > 0) {
            throw BizException.of("权限点「%s」下还有 %d 个子级，请先删除或移走子级", p.getName(), children);
        }
        long binds = rolePermissionMapper.selectCount(Wrappers.<com.hxj.oa.system.entity.RolePermission>lambdaQuery()
                .eq(com.hxj.oa.system.entity.RolePermission::getPermCode, p.getCode()));
        if (binds > 0) {
            throw BizException.of("权限点「%s」仍被 %d 个角色绑定，请先在角色管理中取消勾选再删除", p.getName(), binds);
        }
        // 释放 code 让位给将来同编码的权限点（uk_perm_code 把 deleted 纳入了唯一键，
        // 逻辑删除的记录只能有一条——与角色编码删除同一套处理，见 UniqueKeys）
        SysPermission upd = new SysPermission();
        upd.setId(id);
        upd.setCode(UniqueKeys.release(p.getCode(), id, 128));
        permissionMapper.updateById(upd);
        permissionMapper.deleteById(id);
        log.info("删除权限点 id={} code={} 操作人={}", id, p.getCode(), UserContext.currentUserId());
        snapshotCache.invalidateAfterCommit();
    }

    /* ------------------------------------------------------------------ 内部 */

    private SysPermission requirePerm(Long id) {
        SysPermission p = permissionMapper.selectById(id);
        if (p == null) {
            throw BizException.notFound("权限点不存在: " + id);
        }
        return p;
    }

    private void assertCodeFree(String code, Long excludeId) {
        long n = permissionMapper.selectCount(Wrappers.<SysPermission>lambdaQuery()
                .eq(SysPermission::getCode, code)
                .ne(excludeId != null, SysPermission::getId, excludeId));
        if (n > 0) {
            throw BizException.of("权限编码「%s」已存在", code);
        }
    }

    /**
     * 解析并校验父级：编码必须存在；禁止把自己或自己的祖先设为父级（成环会让树渲染死循环）。
     *
     * @return 父权限点；parentCode 为空返回 null（= 创建/改为一级组点）
     */
    private SysPermission requireParent(String parentCode, SysPermission self) {
        if (!StringUtils.hasText(parentCode)) {
            return null;
        }
        String target = parentCode.trim();
        if (self != null && target.equals(self.getCode())) {
            throw BizException.of("父级不能选自己");
        }
        SysPermission parent = permissionMapper.selectOne(Wrappers.<SysPermission>lambdaQuery()
                .eq(SysPermission::getCode, target).last("LIMIT 1"));
        if (parent == null) {
            throw BizException.of("父级权限点「%s」不存在", target);
        }
        if (self != null && isAncestor(parent, self)) {
            throw BizException.of("父级不能选自己的下级「%s」（会形成循环）", parent.getName());
        }
        return parent;
    }

    /**
     * 把 self 挂到 parent 下是否成环：沿 <b>parent 的祖先链</b>向上走，
     * 碰到 self 即成环（self 变成自己祖先的祖先）。注意方向——「parent 是 self 的现父级」
     * 是正常的重新保存，不是环；环的判定只看 parent 往上会不会绕回 self。
     */
    private boolean isAncestor(SysPermission parent, SysPermission self) {
        String cursor = parent.getParentCode();
        int depth = 0;
        while (StringUtils.hasText(cursor) && depth++ < 16) {
            if (cursor.equals(self.getCode())) {
                return true;
            }
            SysPermission node = permissionMapper.selectOne(Wrappers.<SysPermission>lambdaQuery()
                    .eq(SysPermission::getCode, cursor).last("LIMIT 1"));
            if (node == null) {
                return false;
            }
            cursor = node.getParentCode();
        }
        return false;
    }

    /**
     * 类型与层级只允许一种组合规则：type=1 且无父级 = 组点（纯目录，不参与绑定）；
     * 其余（1有父级 / 2 / 3）都可绑定。拦的是「无父级的 2/3」——没有目录归属的操作点
     * 既不进菜单树也不进权限集弹窗的分组，界面上永远看不见，等于又一个"隐形配置"。
     */
    private void assertTypeShape(Integer permType, SysPermission parent) {
        if (permType != null && permType != 1 && parent == null) {
            throw BizException.of("操作点（类型 2/3）必须挂在某个菜单项或组点下；一级只允许目录（类型 1）");
        }
    }

    /** 同父级下的下一个排序号：当前最大 +10，给中间插队留空隙 */
    private int nextSortNo(SysPermission parent) {
        // 一级组点的 parent_code 为 NULL，MyBatis-Plus 的 eq(column, null) 不合法，须分流查询
        List<SysPermission> siblings = parent == null
                ? permissionMapper.selectList(Wrappers.<SysPermission>lambdaQuery()
                        .isNull(SysPermission::getParentCode))
                : permissionMapper.selectList(Wrappers.<SysPermission>lambdaQuery()
                        .eq(SysPermission::getParentCode, parent.getCode()));
        return siblings.stream().mapToInt(p -> p.getSortNo() == null ? 0 : p.getSortNo()).max().orElse(0) + 10;
    }
}
