package com.hxj.oa.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 人员新建 / 编辑请求。
 *
 * <p>组织归属类字段一律支持「ID 优先、名称兜底」：
 * 前端原型的下拉框绑的是中文名称（如「综合管理中心」），
 * 而库表里存的是 ID。与其让前端拼一遍映射，不如让接口两头都认——
 * 传了 ID 就用 ID，只传名称就按「公司 + 名称」反查，查不到给出明确报错。
 */
@Data
public class UserSaveReq {

    @NotBlank(message = "姓名不能为空")
    @Size(max = 32, message = "姓名不能超过 32 字")
    private String realName;

    @NotBlank(message = "工号不能为空")
    @Size(max = 32, message = "工号不能超过 32 字")
    private String jobNo;

    @NotBlank(message = "登录账号不能为空")
    @Size(max = 64, message = "登录账号不能超过 64 字")
    private String account;

    /** 新建时必填；编辑时留空表示不修改密码 */
    @Size(max = 64, message = "密码不能超过 64 字")
    private String password;

    private Long deptId;
    /** deptId 为空时按名称解析 */
    private String deptName;

    private Long postId;
    /** postId 为空时按名称解析，解析不到则自动在 post 表建一条 */
    private String postName;

    /** 分配角色的编码，优先级高于 roleNames */
    private List<String> roleCodes;
    /** 分配角色的名称（按名称解析为编码） */
    private List<String> roleNames;

    @Size(max = 20, message = "手机号过长")
    private String phone;

    @Size(max = 64, message = "邮箱过长")
    private String email;

    /** 1 在职 0 离职；不传默认在职 */
    private Integer status;
}
