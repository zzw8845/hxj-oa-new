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

    /** 404 不存在 */
    public static BizException notFound(String msg) {
        return new BizException(404, msg);
    }
}
