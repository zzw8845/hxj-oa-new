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

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    public LoginResp login(LoginRequest req) {
        SysUser user = userMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getAccount, req.getAccount())
                .last("LIMIT 1"));
        // 统一错误文案，避免暴露「账号是否存在」
        if (user == null || !passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            throw BizException.unauthorized("账号或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw BizException.forbidden("该账号已停用，请联系管理员");
        }

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
