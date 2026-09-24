package com.hxj.oa.system.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 登录请求 */
@Data
public class LoginRequest {

    /** 登录账号 */
    @NotBlank(message = "账号不能为空")
    private String account;

    /** 登录密码（明文传输依赖 HTTPS，请勿在日志里打印） */
    @NotBlank(message = "密码不能为空")
    private String password;
}
