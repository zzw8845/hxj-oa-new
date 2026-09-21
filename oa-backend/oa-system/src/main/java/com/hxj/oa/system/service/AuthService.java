package com.hxj.oa.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hxj.oa.common.exception.BizException;
import com.hxj.oa.common.security.DataScopeType;
import com.hxj.oa.common.security.LoginUser;
import com.hxj.oa.common.util.JsonColumn;
import com.hxj.oa.common.util.JwtUtils;
import com.hxj.oa.system.dto.LoginRequest;
import com.hxj.oa.system.dto.LoginResp;
import com.hxj.oa.system.entity.Department;
import com.hxj.oa.system.entity.SysPermission;
import com.hxj.oa.system.entity.SysUser;
import com.hxj.oa.system.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 认证与授权装配：
 * 登录 → 校验密码 → 汇总角色/权限/数据范围 → 生成 JWT。
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

    /* ---------------- 登录失败限速 ----------------
       目的：让在线撞库付出时间成本。此前 login 对失败请求不设任何上限，
       攻击者可以每秒成千次地撞「admin + 123456」这类弱口令。

       实现取最简：进程内按账号记失败时间戳，窗口外的自动淘汰，登录成功即清零。
       单实例演示足够；将来多实例部署时换成 Redis 计数即可（接口形状不用变）。
       刻意只按账号、不按 IP：加了 IP 就得把 request 上下文透传进 service，
       而这条路径的价值是"拖慢撞库"，按账号已经达到目的。 */
    private static final int MAX_FAILURES = 10;
    private static final Duration FAILURE_WINDOW = Duration.ofMinutes(5);

    private final Map<String, Deque<Instant>> loginFailures = new ConcurrentHashMap<>();

    /** 超出窗口的失败次数就拒绝，避免攻击者用一个账号无限试。 */
    private void assertNotThrottled(String account) {
        if (account == null) {
            return;
        }
        Deque<Instant> times = loginFailures.get(account);
        if (times == null) {
            return;
        }
        synchronized (times) {
            Instant cutoff = Instant.now().minus(FAILURE_WINDOW);
            while (!times.isEmpty() && times.peekFirst().isBefore(cutoff)) {
                times.pollFirst();
            }
            if (times.size() >= MAX_FAILURES) {
                log.warn("登录被限速 account={} 窗口内失败={} 次", account, times.size());
                throw new BizException(429, "登录失败次数过多，请 5 分钟后再试");
            }
        }
    }

    private void recordFailure(String account) {
        if (account == null) {
            return;
        }
        Deque<Instant> times = loginFailures.computeIfAbsent(account, k -> new ArrayDeque<>());
        synchronized (times) {
            times.addLast(Instant.now());
        }
    }

    private void clearFailures(String account) {
        if (account != null) {
            loginFailures.remove(account);
        }
    }

    public LoginResp login(LoginRequest req) {
        assertNotThrottled(req.getAccount());

        SysUser user = userMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getAccount, req.getAccount())
                .last("LIMIT 1"));
        // 统一错误文案，避免暴露「账号是否存在」
        if (user == null || !passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            recordFailure(req.getAccount());
            throw BizException.unauthorized("账号或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw BizException.forbidden("该账号已停用，请联系管理员");
        }
        clearFailures(req.getAccount());

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

    /** 汇总用户的角色、权限点与数据范围 */
    public LoginUser buildLoginUser(SysUser user) {
        Set<String> roleCodes = new HashSet<>(userRoleMapper.selectRoleCodesByUserId(user.getId()));
        Set<String> permCodes = new HashSet<>(rolePermissionMapper.selectPermCodesByUserId(user.getId()));

        // 数据范围：多角色取最宽
        DataScopeType scope = null;
        for (String s : roleDataScopeMapper.selectScopeTypesByUserId(user.getId())) {
            DataScopeType t = DataScopeType.of(s);
            scope = (scope == null) ? t : scope.wider(t);
        }
        if (scope == null) {
            scope = DataScopeType.SELF;
        }

        Set<Long> scopeDeptIds = new HashSet<>();
        for (String json : roleDataScopeMapper.selectScopeDeptIdsByUserId(user.getId())) {
            scopeDeptIds.addAll(JsonColumn.jsonArrayToLongList(json));
        }

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
                .roleCodes(roleCodes)
                .permCodes(permCodes)
                .dataScope(scope)
                .scopeDeptIds(scopeDeptIds)
                .build();
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
