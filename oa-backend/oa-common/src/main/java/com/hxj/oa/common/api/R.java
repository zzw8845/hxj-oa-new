package com.hxj.oa.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应体。前端按 code 判断成败：0 成功，非 0 失败。
 */
@Data
@JsonInclude(JsonInclude.Include.ALWAYS)
public class R<T> implements Serializable {

    /** 0=成功，其他=失败。⚠ 业务失败时 HTTP 状态码仍是 200，**必须判 code，不要判 HTTP 状态** */
    private int code;
    /** 提示信息，成功时为 success；失败时可直接展示给用户 */
    private String msg;
    /** 业务数据。无返回值时为 null */
    private T data;
    /** 服务端毫秒时间戳 */
    private long timestamp = System.currentTimeMillis();

    public static <T> R<T> ok() {
        return ok(null);
    }

    public static <T> R<T> ok(T data) {
        R<T> r = new R<>();
        r.setCode(0);
        r.setMsg("success");
        r.setData(data);
        return r;
    }

    public static <T> R<T> ok(T data, String msg) {
        R<T> r = ok(data);
        r.setMsg(msg);
        return r;
    }

    public static <T> R<T> fail(int code, String msg) {
        R<T> r = new R<>();
        r.setCode(code);
        r.setMsg(msg);
        return r;
    }

    public static <T> R<T> fail(String msg) {
        return fail(500, msg);
    }
}
