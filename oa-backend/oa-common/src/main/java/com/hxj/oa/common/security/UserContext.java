package com.hxj.oa.common.security;

import com.hxj.oa.common.exception.BizException;

/**
 * 当前请求的登录用户持有者（ThreadLocal）。
 * 由 AuthInterceptor 在请求进入时写入、请求结束时清理。
 */
public final class UserContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    public static LoginUser get() {
        return HOLDER.get();
    }

    /** 取当前用户，未登录直接抛 401 */
    public static LoginUser require() {
        LoginUser u = HOLDER.get();
        if (u == null) {
            throw BizException.unauthorized("未登录或登录已过期");
        }
        return u;
    }

    public static Long currentUserId() {
        return require().getUserId();
    }

    public static Long currentCompanyId() {
        return require().getCompanyId();
    }

    public static Long currentDeptId() {
        return require().getDeptId();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
