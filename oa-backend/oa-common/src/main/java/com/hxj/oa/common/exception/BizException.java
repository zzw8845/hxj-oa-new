package com.hxj.oa.common.exception;

import lombok.Getter;

/**
 * 业务异常。由全局异常处理器统一转成 R.fail。
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(String message) {
        super(message);
        this.code = 400;
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public static BizException of(String format, Object... args) {
        return new BizException(String.format(format, args));
    }

    /** 401 未登录 */
    public static BizException unauthorized(String msg) {
        return new BizException(401, msg);
    }

    /** 403 无权限 */
    public static BizException forbidden(String msg) {
        return new BizException(403, msg);
    }

    /**
     * 403 无权限（带参数）。
     *
     * <p>⚠ 与本类 {@link #of(String, Object...)} 的区别是<b>状态码</b>：那个是 400（参数/业务校验不过），
     * 授权类拒绝统一走 403 —— 前端据此区分"我填错了"与"我没这个权限"。
     * 与上面的单参 {@code forbidden} 共存是安全的：Java 会优先选非变参重载，
     * 因此 {@code forbidden("...")} 的既有语义（不做格式化）不变。
     */
    public static BizException forbidden(String format, Object... args) {
        return new BizException(403, String.format(format, args));
    }

    /** 404 不存在 */
    public static BizException notFound(String msg) {
        return new BizException(404, msg);
    }
}
