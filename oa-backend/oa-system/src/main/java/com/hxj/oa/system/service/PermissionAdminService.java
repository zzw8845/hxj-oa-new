package com.hxj.oa.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hxj.oa.common.exception.BizException;
import com.hxj.oa.common.security.RequirePerm;
import com.hxj.oa.common.security.UserContext;
import com.hxj.oa.system.auth.AuthSnapshotCache;
import com.hxj.oa.system.dto.PermissionSaveReq;
import com.hxj.oa.system.entity.SysPermission;
import com.hxj.oa.system.mapper.RolePermissionMapper;
import com.hxj.oa.system.mapper.SysPermissionMapper;
import org.springframework.context.ApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMethod;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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
    private final ApplicationContext applicationContext;

    /** 后端鉴权契约编码集（懒加载 + 进程内缓存），见 {@link #backendContractCodes()} */
    private volatile Set<String> backendContractCodes;

    /* ------------------------------------------------------------------ 写 */

    @Transactional(rollbackFor = Exception.class)
    public SysPermission create(PermissionSaveReq req) {
        String code = req.getCode().trim();
        assertCodeFree(code, null);
        SysPermission parent = requireParent(req.getParentCode(), null);
        assertTypeShape(req.getPermType(), parent);
        String component = assertComponent(req.getComponent(), req.getPermType(), parent, null);

        SysPermission p = new SysPermission();
        p.setCode(code);
        p.setName(req.getName().trim());
        p.setPermType(req.getPermType());
        p.setParentCode(parent == null ? null : parent.getCode());
        p.setComponent(component);
        p.setSortNo(req.getSortNo() == null ? nextSortNo(parent) : req.getSortNo());
        // sys_permission 无 created_by/updated_by 列（操作人靠 trace 日志留痕），不设审计人字段
        permissionMapper.insert(p);
        log.info("新增权限点 id={} code={} type={} parent={} component={} 操作人={}",
                p.getId(), code, p.getPermType(), p.getParentCode(), component, UserContext.currentUserId());
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
        // 类型与 code 同级：它决定消费形态（loadMenus 只认 1+有父级、绑定守卫只放非组点），
        // 改类型等于把菜单项变操作点/反之，所有已绑定语义跟着漂移。MyBatis-Plus updateById
        // 对 null 不落库，类型可改还会让「清空 component」这类字段无法持久化——一并锁死。
        if (!Objects.equals(req.getPermType(), exist.getPermType())) {
            throw BizException.of("权限类型创建后不可修改（%s 由 %s 改 %s 会改变绑定与菜单语义）",
                    exist.getCode(), exist.getPermType(), req.getPermType());
        }
        String component = assertComponent(req.getComponent(), req.getPermType(), parent, exist.getId());

        SysPermission upd = new SysPermission();
        upd.setId(id);
        upd.setName(req.getName().trim());
        upd.setParentCode(parent == null ? null : parent.getCode());
        // 菜单项的 component 必填非空（见 assertComponent），操作点恒为 null 且类型锁死，
        // 因此这里不会出现「置 null 清字段」的持久化歧义
        upd.setComponent(component);
        if (req.getSortNo() != null) {
            upd.setSortNo(req.getSortNo());
        }
        permissionMapper.updateById(upd);
        log.info("编辑权限点 id={} code={} parent={} component={} 操作人={}",
                id, exist.getCode(), upd.getParentCode(), component, UserContext.currentUserId());
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
        // 后端契约守卫：code 被任何 @RequirePerm 注解引用的，删除会让对应接口对全员失守
        //（重新登录后无人持有该编码，接口一律 403）。若依没有这层护栏，我们有——这是 C6 防自锁
        // 同一条规则铺到权限点侧：尤其 system:perm 自删 = 权限点管理入口永久消失，无恢复路径。
        if (backendContractCodes().contains(p.getCode())) {
            throw BizException.of("权限点「%s」被后端接口鉴权（@RequirePerm）引用，删除会让对应接口对全员失守；如需停用请先下线相关接口",
                    p.getName());
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

    /**
     * 收集后端全部 {@code @RequirePerm} 注解引用的权限编码（方法级 + 类级）。
     *
     * <p>这就是删除黑名单的"事实来源"：每个注解都是后端在声明"这个 code 我在用"，
     * 护栏随代码自动演化——以后加接口、改注解，黑名单自动更新，零维护。
     * 首次删除时懒加载扫描一次 Spring MVC 的全部 handler 方法，进程内缓存
     *（注解是编译进 class 的，运行期不会变）。
     */
    private Set<String> backendContractCodes() {
        Set<String> codes = backendContractCodes;
        if (codes != null) {
            return codes;
        }
        synchronized (this) {
            if (backendContractCodes != null) {
                return backendContractCodes;
            }
            Set<String> collected = new HashSet<>();
            RequestMappingHandlerMapping mapping =
                    applicationContext.getBean(RequestMappingHandlerMapping.class);
            mapping.getHandlerMethods().forEach((info, handlerMethod) -> {
                RequirePerm onClass = handlerMethod.getBeanType().getAnnotation(RequirePerm.class);
                if (onClass != null) {
                    collected.addAll(Arrays.asList(onClass.value()));
                }
                RequirePerm onMethod = handlerMethod.getMethodAnnotation(RequirePerm.class);
                if (onMethod != null) {
                    collected.addAll(Arrays.asList(onMethod.value()));
                }
            });
            backendContractCodes = Collections.unmodifiableSet(collected);
            log.info("后端鉴权契约编码集初始化：{} 个权限点被 @RequirePerm 引用", collected.size());
            return backendContractCodes;
        }
    }

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
     * 类型与层级只允许一种组合规则（对齐若依：目录可嵌目录、菜单挂目录、按钮挂菜单）：
     * type=1 且无父级 = 组点（纯目录，不参与绑定）；type=1 有父级 = 菜单项（可绑定、可挂页面）；
     * type=2/3 = 操作点，必须挂在类型 1 的节点下——拦的是「无父级的 2/3」（界面上永远看不见的
     * 隐形配置）和「挂在操作点下」（操作点当目录没有语义）。
     */
    private void assertTypeShape(Integer permType, SysPermission parent) {
        if (permType != null && permType != 1 && parent == null) {
            throw BizException.of("操作点（类型 2/3）必须挂在某个菜单项或组点下；一级只允许目录（类型 1）");
        }
        if (parent != null && parent.getPermType() != null && parent.getPermType() != 1) {
            throw BizException.of("上级「%s」是操作点，不能作为父级；父级只能是目录或菜单项（类型 1）", parent.getName());
        }
    }

    /**
     * 页面标识（component）规则：菜单项（type=1 且有父级）必填——它就是「加一行=点亮一个已有页面」
     * 的那根线；组点/操作点不得携带（组点不挂页面，操作点不是页面）；全库菜单项内唯一——
     * 两个菜单项指向同一页面，侧边栏高亮/跳转就有歧义。页面代码本身随前端发版，这里配门牌。
     *
     * @return 规整后的 component（菜单项非空；其余恒 null）
     */
    private String assertComponent(String rawComponent, Integer permType, SysPermission parent, Long excludeId) {
        String component = StringUtils.hasText(rawComponent) ? rawComponent.trim() : null;
        boolean isMenuItem = Integer.valueOf(1).equals(permType) && parent != null;
        if (isMenuItem && component == null) {
            throw BizException.of("菜单项必须指定页面标识（前端据此挂载页面），如 work / forms");
        }
        if (!isMenuItem && component != null) {
            throw BizException.of("页面标识只对菜单项有意义（组点不挂页面、操作点不是页面）");
        }
        if (component != null) {
            SysPermission clash = permissionMapper.selectOne(Wrappers.<SysPermission>lambdaQuery()
                    .eq(SysPermission::getComponent, component)
                    .ne(excludeId != null, SysPermission::getId, excludeId)
                    .last("LIMIT 1"));
            if (clash != null) {
                throw BizException.of("页面标识「%s」已被菜单项「%s」使用", component, clash.getName());
            }
        }
        return component;
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
