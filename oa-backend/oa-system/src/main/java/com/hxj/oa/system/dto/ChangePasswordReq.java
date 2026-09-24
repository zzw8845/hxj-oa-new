package com.hxj.oa.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用户自助修改密码请求（区别于管理员的 {@code PUT /api/users/{id}} 重置）。
 *
 * <p>必须验证旧密码：自助改密的唯一凭据就是「我知道旧密码」，
 * 跳过这一步等于任何拿到已登录 token 的人都能改掉账号口令。
 */
@Data
public class ChangePasswordReq {

    /** 原密码 */
    @NotBlank(message = "原密码不能为空")
    private String oldPassword;

    /**
     * 8~64 位。下限 8 是底线（此前演示环境 6 位弱口令挂公网的教训）；
     * 不做「必须含大小写数字符号」之类的复杂度规则 —— 实测那只会逼出
     * 「Abc@1234」这种模式化口令，不如把长度和「不得与原密码相同」守住。
     */
    @NotBlank(message = "新密码不能为空")
    @Size(min = 8, max = 64, message = "新密码长度须在 8~64 位之间")
    private String newPassword;
}
