package com.hxj.oa.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.hxj.oa.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 用户 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class SysUser extends BaseEntity {

    private Long companyId;
    private String jobNo;
    private String account;

    /** BCrypt 哈希。永不出现在响应体中 */
    @JsonIgnore
    private String password;

    private String realName;
    private String phone;
    private String email;
    private Long deptId;
    private Long postId;
    private Integer status;

    /** 是否需强制改密 1 是 0 否 */
    @JsonIgnore
    private Integer pwdResetFlag;

    private LocalDateTime lastLoginAt;
    private Long createdBy;
    private Long updatedBy;
}
