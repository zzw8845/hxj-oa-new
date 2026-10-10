package com.hxj.oa.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hxj.oa.common.exception.BizException;
import com.hxj.oa.common.security.AdminScopeType;
import com.hxj.oa.common.security.DataScopeType;
import com.hxj.oa.common.security.UserContext;
import com.hxj.oa.common.util.AdminScopeHelper;
import com.hxj.oa.common.util.JsonColumn;
import com.hxj.oa.common.util.JsonUtils;
import com.hxj.oa.system.dto.RoleSaveReq;
import com.hxj.oa.system.dto.RoleVO;
import com.hxj.oa.system.auth.AuthSnapshotCache;
import com.hxj.oa.system.entity.Department;
import com.hxj.oa.system.entity.RoleAdminScope;
import com.hxj.oa.system.entity.RoleDataScope;
import com.hxj.oa.system.entity.RolePermission;
import com.hxj.oa.system.entity.SysPermission;
import com.hxj.oa.system.entity.SysRole;
import com.hxj.oa.system.entity.SysUser;
import com.hxj.oa.system.entity.UserRole;
import com.hxj.oa.system.mapper.DepartmentMapper;
import com.hxj.oa.system.mapper.RoleAdminScopeMapper;
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
 * <p><b>内置角色（is_builtin=1）受保护</b>：它们是流程指派规则的锚点（如 ACCOUNTANT / CASHIER / GM）——
 * 删掉会让已部署流程解析不出审批人；改名 / 改绑定的部门岗位会让解析结果**静默漂移**；
 * 清空权限点或摘掉 {@value #ROLE_MANAGE_PERM} 会造成**全局自锁**（系统内再无人能进角色管理，只能改库恢复）。
 * 详见下面三处 C6 护栏。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleAdminService {

    private final SysRoleMapper roleMapper;
    private final SysPermissionMapper permissionMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final RoleDataScopeMapper roleDataScopeMapper;
    private final RoleAdminScopeMapper roleAdminScopeMapper;
    private final UserRoleMapper userRoleMapper;
    private final SysUserMapper userMapper;
    private final DepartmentMapper deptMapper;
    private final OrgResolver orgResolver;

    /**
     * 权限快照缓存。角色/权限的任何写操作都必须让它失效 —— 否则成员重新登录后
     * 会拿到改之前的权限，看起来就是"改了没生效"，而这类问题几乎不可能靠看代码发现。
     * 失效点集中在本类与 {@code UserAdminService}，新增写入口时请一并处理。
     */
    private final AuthSnapshotCache snapshotCache;

    /**
     * 角色管理入口权限点。内置角色一旦丢掉它，系统里**再没有人能进入角色管理**
     * —— 这是比"管理员把自己停用"更严重的全局自锁，只能改库恢复。
     */
    private static final String ROLE_MANAGE_PERM = "system:role";

    private static boolean isBuiltin(SysRole r) {
        return r.getIsBuiltin() != null && r.getIsBuiltin() == 1;
    }

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

        // 管理范围：一个角色一条。**缺行是正常状态**（= 不管人），不是数据缺失 ——
        // 只有显式配过的角色才有行，见 assemble 里的兜底。
        Map<Long, RoleAdminScope> adminScopeByRole = new LinkedHashMap<>();
        for (RoleAdminScope as : roleAdminScopeMapper.selectList(
                Wrappers.<RoleAdminScope>lambdaQuery().in(RoleAdminScope::getRoleId, roleIds))) {
            adminScopeByRole.putIfAbsent(as.getRoleId(), as);
        }

        // 成员：user_role → sys_user.real_name
        Map<Long, List<String>> membersByRole = membersOf(roleIds);

        Map<Long, String> deptNames = deptNameMap(roles.stream().map(SysRole::getDeptId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));

        return roles.stream().map(r -> assemble(r, permCodesByRole, permNameByCode,
                scopeByRole, adminScopeByRole, membersByRole, deptNames)).toList();
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

        // 内置角色保护（C6 第二层）：内置角色是流程指派规则的锚点
        // （selectUserIdsByDeptAndRole / 按名解析角色）。改名或改绑定的部门岗位，
        // 会让**已部署流程**的解析结果静默漂移 —— 不报错，只在某天变成"审批人不对"。
        // 备注可以改；部门/岗位只在显式传入且与现值不同时才拦，避免界面回传原值被误伤。
        if (isBuiltin(exist)) {
            boolean deptProvided = req.getDeptId() != null || StringUtils.hasText(req.getDeptName());
            boolean postProvided = StringUtils.hasText(req.getPostName());
            Long newDeptId = deptProvided
                    ? orgResolver.resolveDeptId(companyId, req.getDeptId(), req.getDeptName())
                    : exist.getDeptId();
            String newPostName = postProvided ? emptyToNull(req.getPostName()) : exist.getPostName();
            if (!Objects.equals(name, exist.getName())
                    || !Objects.equals(newDeptId, exist.getDeptId())
                    || !Objects.equals(newPostName, exist.getPostName())) {
                throw BizException.of("「%s」是内置角色，名称与绑定的部门/岗位是流程指派规则的锚点，不允许修改",
                        exist.getName());
            }
        }

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
        snapshotCache.invalidateAfterCommit();
        return detail(id);
    }

    /** 全量覆盖角色的权限点 */
    @Transactional(rollbackFor = Exception.class)
    public RoleVO updatePermissions(Long id, List<String> permCodes) {
        SysRole r = requireRole(id);
        // 内置角色保护（C6 第二层）：只加"不能改自己"是不够的 —— 系统有两个管理员时，
        // A 仍可把内置角色的权限点清空，B 一起废：这是**全局自锁**，只能改库恢复。
        // 刻意不一刀切禁改：新增权限点后需要给内置角色补授，一刀切会把人逼去改库。
        if (isBuiltin(r)) {
            if (permCodes == null || permCodes.isEmpty()) {
                throw BizException.of("「%s」是内置角色，不允许清空权限点（清空后系统内无人能再进入管理功能）",
                        r.getName());
            }
            List<String> current = detail(id).getPermCodes();
            if (current != null && current.contains(ROLE_MANAGE_PERM) && !permCodes.contains(ROLE_MANAGE_PERM)) {
                throw BizException.of("「%s」是内置角色，不允许移除权限点 %s（移除后系统内无人能再管理角色）",
                        r.getName(), ROLE_MANAGE_PERM);
            }
        }
        overwritePermissions(id, permCodes);
        log.info("配置角色权限 id={} 权限点数={} 操作人={}", id,
                permCodes == null ? 0 : permCodes.size(), UserContext.currentUserId());
        snapshotCache.invalidateAfterCommit();
        return detail(id);
    }

    /** 配置角色数据范围 */
    @Transactional(rollbackFor = Exception.class)
    public RoleVO updateDataScope(Long id, String scopeType, List<Long> deptIds) {
        SysRole r = requireRole(id);
        if (!StringUtils.hasText(scopeType)) {
            throw BizException.of("数据范围不能为空");
        }
        // 内置角色保护（C6 第二层）：范围=self 时管理员看不到任何他人的单据 ——
        // 不报错、不拦登录，只是"数据不见了"，是最难自查的一种能力丢失。
        if (isBuiltin(r) && requireScopeType(scopeType) == DataScopeType.SELF) {
            throw BizException.of("「%s」是内置角色，数据范围不允许设为「仅本人」（设后管理员看不到任何单据）",
                    r.getName());
        }
        overwriteDataScope(id, scopeType, deptIds);
        log.info("配置角色数据范围 id={} scope={} 操作人={}", id, scopeType, UserContext.currentUserId());
        snapshotCache.invalidateAfterCommit();
        return detail(id);
    }

    /**
     * 配置角色管理范围（能【管】哪些部门的人的账号）。
     *
     * <p><b>为什么它不像数据范围那样在新建时就一起给值</b>：两者的缺省语义方向相反。
     * 数据范围缺省会<b>降级</b>成「仅本人」——对管理员是危险的静默收权，所以新建时必须一起给；
     * 管理范围缺省是「不管人」——本来就最窄，没有必须先给的理由。
     * 让新建弹窗少一个能被顺手勾上的授权项，比"两个字段看起来整齐"更重要。
     *
     * <p>⚠ <b>它与数据范围是两个正交维度</b>：改这里不会动 {@code scopeType}，反之亦然。
     *
     * @param scopeType none / dept_subtree / all；传其它值<b>显式失败</b>，不静默降级
     * @param id        角色 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public RoleVO updateAdminScope(Long id, String scopeType) {
        SysRole r = requireRole(id);
        AdminScopeType type = requireAdminScopeType(scopeType);
        // 内置角色保护（C6 第三层，与上面"不允许清空内置角色权限点"同类）：
        // 把 ADMIN 的管理范围从「全公司」收窄，系统里再没有人能管理用户账号，
        // 只能改库恢复 —— 这是又一条全局自锁。
        if (isBuiltin(r) && currentAdminScope(id) == AdminScopeType.ALL && type != AdminScopeType.ALL) {
            throw BizException.of("「%s」是内置角色，管理范围不允许从「全公司」收窄（收窄后系统内无人能再管理用户）",
                    r.getName());
        }
        overwriteAdminScope(id, type);
        log.info("配置角色管理范围 id={} adminScope={} 操作人={}", id, scopeType, UserContext.currentUserId());
        snapshotCache.invalidateAfterCommit();
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
        RoleAdminScope as = roleAdminScopeMapper.selectOne(Wrappers.<RoleAdminScope>lambdaQuery()
                .eq(RoleAdminScope::getRoleId, id).last("LIMIT 1"));
        if (as != null) {
            roleAdminScopeMapper.deleteById(as.getId());
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

    /**
     * 全量覆盖权限点；空列表 = 收回该角色全部权限。
     *
     * <p>两层合法性校验（新建/编辑/配权限三个入口共用本方法，守卫只此一份）：
     * ① 编码必须存在于 sys_permission —— 库里没有的编码写进去等于永不生效的"假权限"；
     * ② 组点（perm_type=1 且无父级，5 个 *:menu）不参与绑定 —— 它是纯目录节点，
     *    只给权限集界面当分组标题、给 loadMenus 当分组数据，绑了没有任何语义，
     *    只会制造「绑了组点没绑菜单项」的歧义状态（与 minimal_seed.sql「组点不绑」口径配对）。
     */
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
        List<SysPermission> all = permissionMapper.selectList(null);
        // 校验①：库里没有的编码 = 假权限
        Set<String> known = all.stream()
                .map(SysPermission::getCode)
                .collect(Collectors.toSet());
        List<String> unknown = distinct.stream().filter(c -> !known.contains(c)).toList();
        if (!unknown.isEmpty()) {
            throw BizException.of("以下权限点不存在：%s", String.join("、", unknown));
        }
        // 校验②：组点是纯目录节点，不参与绑定（界面本就不提供组点勾选，这里拦直调 API 的口子）
        Set<String> groupCodes = all.stream()
                .filter(p -> Integer.valueOf(1).equals(p.getPermType())
                        && !StringUtils.hasText(p.getParentCode()))
                .map(SysPermission::getCode)
                .collect(Collectors.toSet());
        List<String> groups = distinct.stream().filter(groupCodes::contains).toList();
        if (!groups.isEmpty()) {
            throw BizException.of("组点（一级目录）不参与绑定，请勾选具体菜单项：%s", String.join("、", groups));
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
        DataScopeType type = requireScopeType(scopeType);
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

    /** 角色当前生效的管理范围（缺行 = none，与 assemble 的兜底同一口径） */
    private AdminScopeType currentAdminScope(Long roleId) {
        RoleAdminScope as = roleAdminScopeMapper.selectOne(Wrappers.<RoleAdminScope>lambdaQuery()
                .eq(RoleAdminScope::getRoleId, roleId).last("LIMIT 1"));
        return as == null ? AdminScopeType.NONE : AdminScopeType.of(as.getScopeType());
    }

    /** 管理范围是一个角色一条记录，存在即更新，不存在则插入（与 overwriteDataScope 同形） */
    private void overwriteAdminScope(Long roleId, AdminScopeType type) {
        RoleAdminScope exist = roleAdminScopeMapper.selectOne(Wrappers.<RoleAdminScope>lambdaQuery()
                .eq(RoleAdminScope::getRoleId, roleId).last("LIMIT 1"));
        if (exist == null) {
            RoleAdminScope as = new RoleAdminScope();
            as.setRoleId(roleId);
            as.setScopeType(type.getCode());
            roleAdminScopeMapper.insert(as);
        } else {
            roleAdminScopeMapper.update(null, Wrappers.<RoleAdminScope>lambdaUpdate()
                    .eq(RoleAdminScope::getId, exist.getId())
                    .set(RoleAdminScope::getScopeType, type.getCode()));
        }
    }

    /**
     * 严格解析管理范围枚举。
     *
     * <p>与下面的 {@link #requireScopeType} 同理：{@link AdminScopeType#of} 对未知值
     * <b>静默返回 NONE</b>，于是直连接口传个拼错的 {@code dept_subtreeX} 会得到
     * "成功响应 + 管理范围悄悄变成不管人"。写路径不接受静默降级（P5）。
     */
    private static AdminScopeType requireAdminScopeType(String code) {
        if (!StringUtils.hasText(code)) {
            throw BizException.of("管理范围不能为空");
        }
        for (AdminScopeType t : AdminScopeType.values()) {
            if (t.getCode().equalsIgnoreCase(code.trim())) {
                return t;
            }
        }
        throw BizException.of("未知的管理范围「%s」，合法值：none / dept_subtree / all", code);
    }

    /**
     * 严格解析数据范围枚举。
     *
     * <p>{@link DataScopeType#of} 对未知值**静默返回 SELF**（DataScopeType:44），
     * 于是直连接口传 {@code COMPANY} / {@code all} 会得到"成功响应 + 权限悄悄降级"。
     * 写路径不接受静默降级：非法值必须显式失败（P5）。
     */
    private static DataScopeType requireScopeType(String code) {
        if (!StringUtils.hasText(code)) {
            throw BizException.of("数据范围不能为空");
        }
        for (DataScopeType t : DataScopeType.values()) {
            if (t.getCode().equalsIgnoreCase(code.trim())) {
                return t;
            }
        }
        throw BizException.of("未知的数据范围「%s」，合法值：self / dept / center / custom_dept / company", code);
    }

    private RoleVO assemble(SysRole r,
                            Map<Long, List<String>> permCodesByRole,
                            Map<String, String> permNameByCode,
                            Map<Long, RoleDataScope> scopeByRole,
                            Map<Long, RoleAdminScope> adminScopeByRole,
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

        // 管理范围：缺行 = none（最窄），不是"未配置待补"——
        // 所以这里一定要给字符串值而不是留 null，前端才能稳定显示"不管人"。
        RoleAdminScope as = adminScopeByRole.get(r.getId());
        String adminScope = as == null ? AdminScopeType.NONE.getCode() : as.getScopeType();
        vo.setAdminScope(adminScope);
        vo.setAdminScopeLabel(AdminScopeHelper.label(adminScope));

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
