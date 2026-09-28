package com.hxj.oa.system.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 人员视图对象。
 *
 * <p>字段名刻意与 {@code SysUser} 保持一致（realName / jobNo / account / deptId / postId …），
 * 因为前端既有代码直接按这些名字取值；这里只是把「部门名、岗位名、角色」一并补上，
 * 免得前端为了显示一列"部门"再单独请求一次。
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

    /** 手机号：仅 {@code GET /api/users/page} 与 {@code GET /api/users/{id}}（均挂 {@code system:user}）返回；全开的 {@code GET /api/users} <b>不返回该字段</b>（全局 Jackson {@code default-property-inclusion=non_null}，置 null 即不序列化），前端取 {@code row.phone} 得到 {@code undefined} */
    private String phone;
    /** 邮箱：同 {@link #phone} —— 仅门控接口返回，全开的 {@code GET /api/users} 不返回该字段 */
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
