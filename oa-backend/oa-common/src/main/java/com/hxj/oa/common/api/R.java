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

    /** 0=成功，其他=失败 */
    private int code;
    private String msg;
    private T data;
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
