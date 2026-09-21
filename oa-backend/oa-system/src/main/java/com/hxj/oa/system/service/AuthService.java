package com.hxj.oa.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hxj.oa.common.exception.BizException;
import com.hxj.oa.common.security.DataScopeType;
import com.hxj.oa.common.security.LoginUser;
import com.hxj.oa.common.util.JsonColumn;
import com.hxj.oa.common.util.JwtUtils;
import com.hxj.oa.system.dto.LoginRequest;
import com.hxj.oa.system.dto.LoginResp;
import com.hxj.oa.system.auth.AuthSnapshotCache;
import com.hxj.oa.system.auth.LoginThrottle;
import com.hxj.oa.system.entity.Department;
import com.hxj.oa.system.entity.SysPermission;
import com.hxj.oa.system.entity.SysUser;
import com.hxj.oa.system.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 认证与授权装配：
 * 登录 → 校验密码 → 汇总角色/权限/数据范围 → 生成 JWT。
 *
 * <p>两处外部依赖都做了「可替换实现」，而不是直接把 Redis 写死在业务里：
 * <ul>
 *   <li>{@link LoginThrottle} —— 蓝绿双实例下必须是 Redis 计数，否则轮流打两个实例就能绕过限速；</li>
 *   <li>{@link AuthSnapshotCache} —— 缓存登录时要跑的 4 次权限查询，
 *       失效信号同样必须跨实例，理由与上面相同。</li>
 * </ul>
 * 两者在 {@code oa.redis.enabled=false} 时自动回落为进程内实现，本地联调零外部依赖。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final RoleDataScopeMapper roleDataScopeMapper;
    private final DepartmentMapper departmentMapper;
    private final SysPermissionMapper permissionMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final LoginThrottle loginThrottle;
    private final AuthSnapshotCache snapshotCache;

    public LoginResp login(LoginRequest req) {
        loginThrottle.assertAllowed(req.getAccount());

        SysUser user = userMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getAccount, req.getAccount())
                .last("LIMIT 1"));
        // 统一错误文案，避免暴露「账号是否存在」
        if (user == null || !passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            loginThrottle.onFailure(req.getAccount());
            throw BizException.unauthorized("账号或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw BizException.forbidden("该账号已停用，请联系管理员");
        }
        loginThrottle.onSuccess(req.getAccount());

        SysUser upd = new SysUser();
        upd.setId(user.getId());
        upd.setLastLoginAt(LocalDateTime.now());
        userMapper.updateById(upd);

        LoginUser loginUser = buildLoginUser(user);
        String token = jwtUtils.generate(loginUser);

        LoginResp resp = new LoginResp();
        resp.setToken(token);
        resp.setExpiresIn(jwtUtils.getExpireMillis() / 1000);
        resp.setUser(loginUser);
        resp.setMenus(loadMenus(user.getId()));
        log.info("用户登录成功 userId={} account={} roles={} scope={}",
                user.getId(), user.getAccount(), loginUser.getRoleCodes(), loginUser.getDataScope());
        return resp;
    }

    /**
     * 汇总用户的角色、权限点与数据范围。
     *
     * <p>结果按 userId 缓存（见 {@link AuthSnapshotCache}）。这里加缓存不只是省几次查询：
     * 测试环境的库在本机之外，这 4 条 JOIN 每条都要付一次网络往返，
     * 单次登录累计能到几十毫秒，而它们的结果在两次"改角色"之间是完全不变的。
     */
    public LoginUser buildLoginUser(SysUser user) {
        AuthSnapshotCache.AuthSnapshot snapshot = loadSnapshot(user.getId());

        String deptName = null;
        String deptPath = null;
        if (user.getDeptId() != null) {
            Department dept = departmentMapper.selectById(user.getDeptId());
            if (dept != null) {
                deptName = dept.getName();
                deptPath = dept.getPath();
            }
        }

        return LoginUser.builder()
                .userId(user.getId())
                .account(user.getAccount())
                .realName(user.getRealName())
                .companyId(user.getCompanyId())
                .deptId(user.getDeptId())
                .deptName(deptName)
                .deptPath(deptPath)
                .roleCodes(snapshot.roleCodes())
                .permCodes(snapshot.permCodes())
                .dataScope(snapshot.dataScope())
                .scopeDeptIds(snapshot.scopeDeptIds())
                .build();
    }

    /** 先查缓存，未命中再查库并回填 */
    private AuthSnapshotCache.AuthSnapshot loadSnapshot(Long userId) {
        AuthSnapshotCache.AuthSnapshot cached = snapshotCache.get(userId);
        if (cached != null) {
            log.debug("命中认证快照缓存 userId={} 角色数={} 权限点数={}",
                    userId, cached.roleCodes().size(), cached.permCodes().size());
            return cached;
        }
        AuthSnapshotCache.AuthSnapshot fresh = querySnapshot(userId);
        snapshotCache.put(userId, fresh);
        return fresh;
    }

    /** 四次查询：角色编码 / 权限点 / 数据范围类型 / 指定的部门集合 */
    private AuthSnapshotCache.AuthSnapshot querySnapshot(Long userId) {
        Set<String> roleCodes = new HashSet<>(userRoleMapper.selectRoleCodesByUserId(userId));
        Set<String> permCodes = new HashSet<>(rolePermissionMapper.selectPermCodesByUserId(userId));

        // 数据范围：多角色取最宽
        DataScopeType scope = null;
        for (String s : roleDataScopeMapper.selectScopeTypesByUserId(userId)) {
            DataScopeType t = DataScopeType.of(s);
            scope = (scope == null) ? t : scope.wider(t);
        }
        if (scope == null) {
            // 没有任何角色/数据范围配置时收紧到「仅本人」。漏配不该被解释成放权。
            scope = DataScopeType.SELF;
        }

        Set<Long> scopeDeptIds = new HashSet<>();
        for (String json : roleDataScopeMapper.selectScopeDeptIdsByUserId(userId)) {
            scopeDeptIds.addAll(JsonColumn.jsonArrayToLongList(json));
        }

        // 用 unmodifiableSet 而不是 Set.copyOf：copyOf 遇到 null 元素直接 NPE，
        // 而 scopeDeptIds 来自 JSON 解析，脏数据里带个 null 是完全可能的 ——
        // 那会让"登录"整体 500，代价远大于收益。这里只需要"不可被下游改写"这一条保证。
        return new AuthSnapshotCache.AuthSnapshot(
                Collections.unmodifiableSet(roleCodes),
                Collections.unmodifiableSet(permCodes),
                scope,
                Collections.unmodifiableSet(scopeDeptIds));
    }

    public List<LoginResp.MenuItem> loadMenus(Long userId) {
        return permissionMapper.selectByUserId(userId).stream()
                .filter(p -> p.getPermType() != null && p.getPermType() == 1)
                .map(p -> {
                    LoginResp.MenuItem m = new LoginResp.MenuItem();
                    m.setCode(p.getCode());
                    m.setName(p.getName());
                    m.setParentCode(p.getParentCode());
                    m.setSortNo(p.getSortNo());
                    return m;
                })
                .toList();
    }

    /** 权限点全量（供前端按钮级控制） */
    public List<SysPermission> loadPermissions(Long userId) {
        return permissionMapper.selectByUserId(userId);
    }
}
