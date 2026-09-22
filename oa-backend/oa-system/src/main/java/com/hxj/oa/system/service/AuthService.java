package com.hxj.oa.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hxj.oa.common.exception.BizException;
import com.hxj.oa.common.security.DataScopeType;
import com.hxj.oa.common.security.LoginUser;
import com.hxj.oa.common.util.JsonColumn;
import com.hxj.oa.common.util.JwtUtils;
import com.hxj.oa.system.auth.TokenRevocationStore;
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
    private final TokenRevocationStore revocationStore;

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
        // 首登强制改密：管理员创建账号/重置口令时 pwd_reset_flag=1（UserAdminService），
        // 前端据此弹不可跳过的改密框。服务端只负责如实告知，不在这里拦 ——
        // 拦截会破坏「带 token 的 API 调用」这一层（脚本/自动化也要能登录）。
        resp.setMustChangePassword(user.getPwdResetFlag() != null && user.getPwdResetFlag() == 1);
        log.info("用户登录成功 userId={} account={} roles={} scope={}",
                user.getId(), user.getAccount(), loginUser.getRoleCodes(), loginUser.getDataScope());
        return resp;
    }

    /**
     * 用户自助修改密码。
     *
     * <p>与管理员重置（{@code UserAdminService#update}）的三点区别：
     * 必须验旧密码；新密码不得与旧密码相同（防「强制改密」被原样填一遍绕过）；
     * 成功后 pwd_reset_flag 归零（首登强制的闸门就在这里打开）。
     * 当前 token 不作废：改密不影响权限快照内容，让旧 token 自然到期即可。
     */
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.notFound("用户不存在");
        }
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            // 与登录同口径：不区分「旧密码错」，避免给撞库者额外信号
            throw BizException.of("原密码不正确");
        }
        if (passwordEncoder.matches(newPassword, user.getPassword())) {
            throw BizException.of("新密码不能与原密码相同");
        }
        SysUser upd = new SysUser();
        upd.setId(userId);
        upd.setPassword(passwordEncoder.encode(newPassword));
        upd.setPwdResetFlag(0);
        userMapper.updateById(upd);
        log.info("用户自助修改密码 userId={} account={}", userId, user.getAccount());
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

    /**
     * 登出：**真的作废这一个 token**（而不只是让前端把本地 token 删掉）。
     *
     * <p>为什么必须做：无状态 JWT 在到期前一直有效（当前 720 分钟）。
     * 只清前端的话，界面上"已经退出"，但拿到过这张 token 的人仍能继续调用接口
     * —— 共用电脑、或切换登录身份后，旧身份的权限并没有消失。
     *
     * <p>为什么按 token 而不是按用户：按 userId 拉黑会把登出后**重新登录**
     * 拿到的新 token 一起废掉，变成"登出之后再也登不进来"。
     *
     * @param authorization 原始 {@code Authorization} 头。允许为空（此时什么也不做），
     *                      因为登出不该因为头缺失而报错 —— 前端的语义是"尽力作废，然后清本地"。
     */
    public void logout(String authorization) {
        String token = extractBearer(authorization);
        if (token == null) {
            return;
        }
        JwtUtils.ParsedToken parsed;
        try {
            parsed = jwtUtils.parseFull(token);
        } catch (RuntimeException e) {
            // 已过期/被改过的 token 本来就过不了拦截器，无需吊销
            return;
        }
        long remain = parsed.expiration().getTime() - System.currentTimeMillis();
        if (parsed.tokenId() != null && remain > 0) {
            revocationStore.revoke(parsed.tokenId(), remain);
        }
    }

    /** 取出 "Bearer xxx" 里的 xxx；不是这个格式就返回 null。 */
    private String extractBearer(String authorization) {
        if (authorization == null) {
            return null;
        }
        String v = authorization.trim();
        int sp = v.indexOf(' ');
        if (sp < 0) {
            return null;
        }
        if (!"Bearer".equalsIgnoreCase(v.substring(0, sp))) {
            return null;
        }
        String token = v.substring(sp + 1).trim();
        return token.isEmpty() ? null : token;
    }

}
