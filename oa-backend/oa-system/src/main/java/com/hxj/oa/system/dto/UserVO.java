package com.hxj.oa.system.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 人员视图对象。
 *
 * <p>字段名与 {@code SysUser} 保持一致（realName / jobNo / account / deptId / postId …），
 * 装配时可由实体直接拷贝，少一层字段映射。
 *
 * <p>另外补上「部门名、岗位名、角色」等<b>反查字段</b>：它们本身不是 {@code SysUser} 的列，
 * 但人员视图的完整语义就包含「在哪个部门、什么岗位、担什么角色」，
 * 由服务端一次组装好，比让调用方拿着 id 逐个回查更省请求、也更不容易漏。
 */
@Data
public class UserVO {

    /** 用户 ID */
    private Long id;
    /** 公司 ID */
    private Long companyId;
    /** 姓名 */
    private String realName;
    /** 工号 */
    private String jobNo;
    /** 登录账号 */
    private String account;

    /** 部门 ID */
    private Long deptId;
    /** 部门名称，由 deptId 反查得到 */
    private String deptName;

    /** 岗位 ID */
    private Long postId;
    /** 岗位名称，由 postId 反查得到 */
    private String postName;

    /** 手机号 */
    private String phone;
    /** 邮箱 */
    private String email;

    /** 1 在职 0 离职 */
    private Integer status;
    /** 最后一次登录时间 */
    private LocalDateTime lastLoginAt;

    /** 已分配角色编码 */
    private List<String> roleCodes;
    /** 已分配角色名称，供「分配角色」列直接展示 */
    private List<String> roleNames;
}
