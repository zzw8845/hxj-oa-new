package com.hxj.oa.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hxj.oa.common.exception.BizException;
import com.hxj.oa.common.security.DataScopeType;
import com.hxj.oa.common.security.UserContext;
import com.hxj.oa.common.util.JsonColumn;
import com.hxj.oa.common.util.JsonUtils;
import com.hxj.oa.system.dto.RoleSaveReq;
import com.hxj.oa.system.dto.RoleVO;
import com.hxj.oa.system.entity.Department;
import com.hxj.oa.system.entity.RoleDataScope;
import com.hxj.oa.system.entity.RolePermission;
import com.hxj.oa.system.entity.SysPermission;
import com.hxj.oa.system.entity.SysRole;
import com.hxj.oa.system.entity.SysUser;
import com.hxj.oa.system.entity.UserRole;
import com.hxj.oa.system.mapper.DepartmentMapper;
import com.hxj.oa.system.mapper.RoleDataScopeMapper;
import com.hxj.oa.system.mapper.RolePermissionMapper;
import com.hxj.oa.system.mapper.SysPermissionMapper;
import com.hxj.oa.system.mapper.SysRoleMapper;
import com.hxj.oa.system.mapper.SysUserMapper;
import com.hxj.oa.system.mapper.UserRoleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 角色管理（写侧）：新增角色、改属性、配权限点、配数据范围、删角色。
 *
 * <p><b>权限点与数据范围分开走两个接口</b>，因为它们的生效时机不同：
 * <ul>
 *   <li>权限点（功能权限）编进 JWT，改动后需重新登录才生效；</li>
 *   <li>数据范围（行级权限）同样编进 JWT，行为一致。</li>
 * </ul>
 * 两者都会在响应里带出「需重新登录」的提示，避免用户改完发现"没生效"而困惑。
 *
 * <p><b>内置角色（is_builtin=1）不可删除</b>：它们是流程指派规则的锚点
 * （如 ACCOUNTANT / CASHIER / GM），删掉会让已部署的流程解析不出审批人。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleAdminService {

    private final SysRoleMapper roleMapper;
    private final SysPermissionMapper permissionMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final RoleDataScopeMapper roleDataScopeMapper;
    private final UserRoleMapper userRoleMapper;
    private final SysUserMapper userMapper;
    private final DepartmentMapper deptMapper;
    private final OrgResolver orgResolver;

    /* ------------------------------------------------------------------ 查询 */

    public List<RoleVO> listWithDetail(Long companyId) {
        List<SysRole> roles = roleMapper.selectList(Wrappers.<SysRole>lambdaQuery()
                .eq(companyId != null, SysRole::getCompanyId, companyId)
                .orderByAsc(SysRole::getId));
        if (roles.isEmpty()) {
            return List.of();
        }
        List<Long> roleIds = roles.stream().map(SysRole::getId).toList();

        // 权限点：role_permission → perm_code
        Map<Long, List<String>> permCodesByRole = rolePermissionMapper
                .selectList(Wrappers.<RolePermission>lambdaQuery().in(RolePermission::getRoleId, roleIds))
                .stream()
                .collect(Collectors.groupingBy(RolePermission::getRoleId,
                        Collectors.mapping(RolePermission::getPermCode, Collectors.toList())));

        // code → 中文名，供卡片标签直接展示
        Map<String, String> permNameByCode = permissionMapper.selectList(null).stream()
                .collect(Collectors.toMap(SysPermission::getCode, SysPermission::getName, (a, b) -> a));

        // 数据范围：一个角色一条
        Map<Long, RoleDataScope> scopeByRole = new LinkedHashMap<>();
        for (RoleDataScope ds : roleDataScopeMapper.selectList(
                Wrappers.<RoleDataScope>lambdaQuery().in(RoleDataScope::getRoleId, roleIds))) {
            scopeByRole.putIfAbsent(ds.getRoleId(), ds);
        }

        // 成员：user_role → sys_user.real_name
        Map<Long, List<String>> membersByRole = membersOf(roleIds);

        Map<Long, String> deptNames = deptNameMap(roles.stream().map(SysRole::getDeptId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));

        return roles.stream().map(r -> assemble(r, permCodesByRole, permNameByCode,
                scopeByRole, membersByRole, deptNames)).toList();
    }

    public RoleVO detail(Long id) {
        SysRole r = requireRole(id);
        return listWithDetail(r.getCompanyId()).stream()
                .filter(v -> Objects.equals(v.getId(), id))
                .findFirst()
                .orElseThrow(() -> BizException.notFound("角色不存在: " + id));
    }

    /* ------------------------------------------------------------------ 写 */

    @Transactional(rollbackFor = Exception.class)
    public RoleVO create(RoleSaveReq req) {
        Long companyId = UserContext.currentCompanyId();
        String name = req.getName().trim();

        assertNameFree(companyId, name, null);

        SysRole r = new SysRole();
        r.setCompanyId(companyId);
        r.setName(name);
        r.setCode(StringUtils.hasText(req.getCode()) ? req.getCode().trim() : nextRoleCode(companyId));
        r.setDeptId(orgResolver.resolveDeptId(companyId, req.getDeptId(), req.getDeptName()));
        r.setPostName(emptyToNull(req.getPostName()));
        r.setIsBuiltin(0);
        r.setStatus(1);
        r.setRemark(emptyToNull(req.getRemark()));
        r.setCreatedBy(UserContext.currentUserId());
        roleMapper.insert(r);

        overwritePermissions(r.getId(), req.getPermCodes());
        overwriteDataScope(r.getId(), req.getScopeType(), req.getScopeDeptIds());

        log.info("新建角色 id={} code={} name={} 操作人={}",
                r.getId(), r.getCode(), name, UserContext.currentUserId());
        return detail(r.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public RoleVO update(Long id, RoleSaveReq req) {
        SysRole exist = requireRole(id);
        Long companyId = exist.getCompanyId();
        String name = req.getName().trim();
        assertNameFree(companyId, name, id);

        SysRole upd = new SysRole();
        upd.setId(id);
        upd.setName(name);
        upd.setDeptId(orgResolver.resolveDeptId(companyId, req.getDeptId(), req.getDeptName()));
        upd.setPostName(emptyToNull(req.getPostName()));
        upd.setRemark(emptyToNull(req.getRemark()));
        upd.setUpdatedBy(UserContext.currentUserId());
        roleMapper.updateById(upd);

        // 权限点/数据范围：只有显式带了才覆盖（编辑基本信息时不应顺手清空权限）
        if (req.getPermCodes() != null) {
            overwritePermissions(id, req.getPermCodes());
        }
        if (StringUtils.hasText(req.getScopeType())) {
            overwriteDataScope(id, req.getScopeType(), req.getScopeDeptIds());
        }
        log.info("编辑角色 id={} name={} 操作人={}", id, name, UserContext.currentUserId());
        return detail(id);
    }

    /** 全量覆盖角色的权限点 */
    @Transactional(rollbackFor = Exception.class)
    public RoleVO updatePermissions(Long id, List<String> permCodes) {
        requireRole(id);
        overwritePermissions(id, permCodes);
        log.info("配置角色权限 id={} 权限点数={} 操作人={}", id,
                permCodes == null ? 0 : permCodes.size(), UserContext.currentUserId());
        return detail(id);
    }

    /** 配置角色数据范围 */
    @Transactional(rollbackFor = Exception.class)
    public RoleVO updateDataScope(Long id, String scopeType, List<Long> deptIds) {
        requireRole(id);
        if (!StringUtils.hasText(scopeType)) {
            throw BizException.of("数据范围不能为空");
        }
        overwriteDataScope(id, scopeType, deptIds);
        log.info("配置角色数据范围 id={} scope={} 操作人={}", id, scopeType, UserContext.currentUserId());
        return detail(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        SysRole r = requireRole(id);
        if (r.getIsBuiltin() != null && r.getIsBuiltin() == 1) {
            throw BizException.of("「%s」是内置角色，流程指派规则依赖其编码，不允许删除", r.getName());
        }
        List<UserRole> users = userRoleMapper.selectList(Wrappers.<UserRole>lambdaQuery()
                .eq(UserRole::getRoleId, id));
        if (!users.isEmpty()) {
            throw BizException.of("角色「%s」下还有 %d 名成员，请先调整这些成员的角色",
                    r.getName(), users.size());
        }
        // 先清干净关联，再删角色本体，避免留下悬空的权限点/数据范围
        rolePermissionMapper.physicalDeleteByRoleId(id);
        RoleDataScope ds = roleDataScopeMapper.selectOne(Wrappers.<RoleDataScope>lambdaQuery()
                .eq(RoleDataScope::getRoleId, id).last("LIMIT 1"));
        if (ds != null) {
            roleDataScopeMapper.deleteById(ds.getId());
        }
        // 释放 code 让位给将来同编码的角色（原因见 UniqueKeys#release：
        // uk_role_company_code 把 deleted 纳入了唯一键，逻辑删除的记录只能有一条）
        SysRole upd = new SysRole();
        upd.setId(id);
        upd.setCode(UniqueKeys.release(r.getCode(), id, 64));
        upd.setUpdatedBy(UserContext.currentUserId());
        roleMapper.updateById(upd);
        roleMapper.deleteById(id);
        log.info("删除角色 id={} code={} 操作人={}", id, r.getCode(), UserContext.currentUserId());
    }

    /* ------------------------------------------------------------------ 内部 */

    private SysRole requireRole(Long id) {
        SysRole r = roleMapper.selectById(id);
        if (r == null) {
            throw BizException.notFound("角色不存在: " + id);
        }
        return r;
    }

    private void assertNameFree(Long companyId, String name, Long excludeId) {
        long n = roleMapper.selectCount(Wrappers.<SysRole>lambdaQuery()
                .eq(companyId != null, SysRole::getCompanyId, companyId)
                .eq(SysRole::getName, name)
                .ne(excludeId != null, SysRole::getId, excludeId));
        if (n > 0) {
            throw BizException.of("角色名称「%s」已存在", name);
        }
    }

    /** 全量覆盖权限点；空列表 = 收回该角色全部权限 */
    private void overwritePermissions(Long roleId, List<String> permCodes) {
        rolePermissionMapper.physicalDeleteByRoleId(roleId);
        if (permCodes == null || permCodes.isEmpty()) {
            return;
        }
        List<String> distinct = permCodes.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        // 校验合法性：库里没有的编码写进去，等于给了一个永远不会生效的"假权限"
        Set<String> known = permissionMapper.selectList(null).stream()
                .map(SysPermission::getCode)
                .collect(Collectors.toSet());
        List<String> unknown = distinct.stream().filter(c -> !known.contains(c)).toList();
        if (!unknown.isEmpty()) {
            throw BizException.of("以下权限点不存在：%s", String.join("、", unknown));
        }
        Long operator = UserContext.currentUserId();
        for (String code : distinct) {
            RolePermission rp = new RolePermission();
            rp.setRoleId(roleId);
            rp.setPermCode(code);
            rp.setCreatedBy(operator);
            rolePermissionMapper.insert(rp);
        }
    }

    /** 数据范围是一个角色一条记录，存在即更新，不存在则插入 */
    private void overwriteDataScope(Long roleId, String scopeType, List<Long> deptIds) {
        DataScopeType type = DataScopeType.of(scopeType);
        String deptJson = (type == DataScopeType.CUSTOM_DEPT && deptIds != null && !deptIds.isEmpty())
                ? JsonUtils.toJson(deptIds.stream().filter(Objects::nonNull).distinct().toList())
                : null;

        RoleDataScope exist = roleDataScopeMapper.selectOne(Wrappers.<RoleDataScope>lambdaQuery()
                .eq(RoleDataScope::getRoleId, roleId).last("LIMIT 1"));
        if (exist == null) {
            RoleDataScope ds = new RoleDataScope();
            ds.setRoleId(roleId);
            ds.setScopeType(type.getCode());
            ds.setDeptIds(deptJson);
            roleDataScopeMapper.insert(ds);
        } else {
            // dept_ids 可能要从「有值」改成「无值」，必须用 UpdateWrapper 显式写 null：
            // MyBatis-Plus 的 updateById 默认忽略 null 字段，会把旧值留在库里
            roleDataScopeMapper.update(null, Wrappers.<RoleDataScope>lambdaUpdate()
                    .eq(RoleDataScope::getId, exist.getId())
                    .set(RoleDataScope::getScopeType, type.getCode())
                    .set(RoleDataScope::getDeptIds, deptJson));
        }
    }

    private RoleVO assemble(SysRole r,
                            Map<Long, List<String>> permCodesByRole,
                            Map<String, String> permNameByCode,
                            Map<Long, RoleDataScope> scopeByRole,
                            Map<Long, List<String>> membersByRole,
                            Map<Long, String> deptNames) {
        RoleVO vo = new RoleVO();
        vo.setId(r.getId());
        vo.setCompanyId(r.getCompanyId());
        vo.setCode(r.getCode());
        vo.setName(r.getName());
        vo.setDeptId(r.getDeptId());
        // deptId 可空（「对应部门」不选 = 全公司），必须先判空：
        // deptNameMap 无 id 时返回 Map.of()，不可变 Map 的 get(null) 会抛 NPE。
        vo.setDeptName(r.getDeptId() == null ? null : deptNames.get(r.getDeptId()));
        vo.setPostName(r.getPostName());
        vo.setIsBuiltin(r.getIsBuiltin());
        vo.setStatus(r.getStatus());
        vo.setRemark(r.getRemark());

        List<String> codes = permCodesByRole.getOrDefault(r.getId(), List.of());
        vo.setPermCodes(codes);
        vo.setPermNames(codes.stream().map(c -> permNameByCode.getOrDefault(c, c)).toList());

        RoleDataScope ds = scopeByRole.get(r.getId());
        String scopeType = ds == null ? DataScopeType.SELF.getCode() : ds.getScopeType();
        vo.setScopeType(scopeType);
        vo.setScopeLabel(scopeLabel(scopeType));
        if (ds != null) {
            vo.setScopeDeptIds(JsonColumn.jsonArrayToLongList(ds.getDeptIds()));
        } else {
            vo.setScopeDeptIds(List.of());
        }

        vo.setMembers(membersByRole.getOrDefault(r.getId(), List.of()));
        return vo;
    }

    /** 数据范围中文名，前端直接展示 */
    public static String scopeLabel(String scopeType) {
        return switch (scopeType == null ? "self" : scopeType) {
            case "self" -> "本人单据";
            case "dept" -> "本部门单据";
            case "center" -> "本中心单据";
            case "custom_dept" -> "指定部门单据";
            case "company" -> "全公司单据";
            default -> scopeType;
        };
    }

    private Map<Long, List<String>> membersOf(List<Long> roleIds) {
        List<UserRole> links = userRoleMapper.selectList(Wrappers.<UserRole>lambdaQuery()
                .in(UserRole::getRoleId, roleIds));
        if (links.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> nameById = userMapper
                .selectBatchIds(links.stream().map(UserRole::getUserId).distinct().toList())
                .stream().collect(Collectors.toMap(SysUser::getId, SysUser::getRealName, (a, b) -> a));
        Map<Long, List<String>> out = new LinkedHashMap<>();
        for (UserRole l : links) {
            String name = nameById.get(l.getUserId());
            if (name != null) {
                out.computeIfAbsent(l.getRoleId(), k -> new ArrayList<>()).add(name);
            }
        }
        return out;
    }

    private Map<Long, String> deptNameMap(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return deptMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Department::getId, Department::getName, (a, b) -> a));
    }

    /** 生成不冲突的角色编码：CUSTOM_01、CUSTOM_02 … */
    private String nextRoleCode(Long companyId) {
        for (int i = 1; i <= 999; i++) {
            String code = String.format("CUSTOM_%02d", i);
            boolean used = roleMapper.selectCount(Wrappers.<SysRole>lambdaQuery()
                    .eq(companyId != null, SysRole::getCompanyId, companyId)
                    .eq(SysRole::getCode, code)) > 0;
            if (!used) {
                return code;
            }
        }
        throw BizException.of("自定义角色编码已用尽（CUSTOM_01 ~ CUSTOM_999）");
    }

    private String emptyToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }
}
