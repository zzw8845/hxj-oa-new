package com.hxj.oa.system.controller;

import com.hxj.oa.common.annotation.Audit;
import com.hxj.oa.common.api.R;
import com.hxj.oa.common.security.LoginUser;
import com.hxj.oa.common.security.UserContext;
import com.hxj.oa.system.dto.LoginRequest;
import com.hxj.oa.system.dto.LoginResp;
import com.hxj.oa.system.entity.SysPermission;
import com.hxj.oa.system.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 认证入口 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 登录。
     *
     * <p>留痕说明：本接口在 AuthInterceptor 白名单里，进控制器时还没有登录态，
     * 所以身份由审计切面从响应体的 {@code LoginResp.user} 反解（见 AuditAspect.resolveUser）。
     * 登录失败时 userId 为空，但 IP / UA / 失败原因照记 —— 撞库尝试正是靠这些字段发现的。
     *
     * <p>切面**不会**记录请求体原文，明文密码不会进审计表。
     */
    @PostMapping("/login")
    @Audit(module = "auth", action = "login")
    public R<LoginResp> login(@Valid @RequestBody LoginRequest req) {
        return R.ok(authService.login(req), "登录成功");
    }

    /** 当前登录用户（前端刷新后回填用户信息与权限） */
    @GetMapping("/me")
    public R<LoginUser> me() {
        return R.ok(UserContext.require());
    }

    /**
     * 用户自助修改密码（区别于管理员的 {@code PUT /api/users/{id}} 重置）。
     *
     * <p>在 /api/** 拦截链内：必须持有效 token 才能调 —— 「本人」这个身份由 token 保证。
     * 旧密码错误 / 新密码与旧相同会在服务端明确拒绝（见 {@link AuthService#changePassword}）。
     */
    @PutMapping("/password")
    @Audit(module = "auth", action = "changePassword")
    public R<Void> changePassword(@Valid @RequestBody com.hxj.oa.system.dto.ChangePasswordReq req) {
        authService.changePassword(UserContext.require().getUserId(), req.getOldPassword(), req.getNewPassword());
        return R.ok(null, "密码已修改");
    }

    /** 当前用户拥有的菜单权限点（permType=1，平铺列表，前端按 parentCode 自行组树） */
    @GetMapping("/menus")
    public R<List<LoginResp.MenuItem>> menus() {
        return R.ok(authService.loadMenus(UserContext.require().getUserId()));
    }

    /** 权限点全量，供前端按钮级控制 */
    @GetMapping("/permissions")
    public R<List<SysPermission>> permissions() {
        return R.ok(authService.loadPermissions(UserContext.require().getUserId()));
    }

    /**
     * 登出：**服务端把这一张 token 作废**（此前只是返回一句"已登出"，什么也没做）。
     *
     * <p>为什么改成真吊销：token 有效期 720 分钟，只清前端等于"界面退出了、
     * 凭证还能用 12 小时"。切换登录身份、共用电脑都会踩到。
     * 实现见 {@code AuthService#logout}：按 jti 拉黑，只作废当前这一张。
     *
     * <p>头缺失或 token 已过期时**不报错** —— 登出的语义是"尽力作废"，
     * 前端随后无论如何都会清掉本地 token。
     *
     * @param authorization 当前令牌，形如 {@code Bearer eyJ...}；不传也能调通
     */
    @PostMapping("/logout")
    @Audit(module = "auth", action = "logout")
    public R<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authService.logout(authorization);
        return R.ok(null, "已登出");
    }
}
