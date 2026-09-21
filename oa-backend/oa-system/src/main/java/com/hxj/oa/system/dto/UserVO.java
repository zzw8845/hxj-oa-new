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

    private Long id;
    private Long companyId;
    private String realName;
    private String jobNo;
    private String account;

    private Long deptId;
    /** 部门名称，由 deptId 反查得到 */
    private String deptName;

    private Long postId;
    /** 岗位名称，由 postId 反查得到 */
    private String postName;

    private String phone;
    private String email;

    /** 1 在职 0 离职 */
    private Integer status;
    private LocalDateTime lastLoginAt;

    /** 已分配角色编码 */
    private List<String> roleCodes;
    /** 已分配角色名称，供「分配角色」列直接展示 */
    private List<String> roleNames;
}
