package com.hxj.oa.system.dto;

import lombok.Data;

import java.util.List;

/**
 * 角色视图对象。
 *
 * <p>相比 {@code SysRole} 补了三样：<b>权限点明细、数据范围、成员列表</b>。
 * 这三样共同构成「这个角色是什么」的完整答案（能干什么、管多大范围、谁在用它），
 * 缺任何一样都只是角色的一个片段 —— 它们散在关联表里，裸实体给不出。
 */
@Data
public class RoleVO {

    /** 角色 ID */
    private Long id;
    /** 公司 ID */
    private Long companyId;
    /** 角色编码，如 ADMIN、CUSTOM_01（后端按编码判权，改名不影响） */
    private String code;
    /** 角色名称 */
    private String name;

    /** 归属部门 ID（可空） */
    private Long deptId;
    /** 归属部门名称 */
    private String deptName;

    /** 对应岗位名称（展示用） */
    private String postName;

    /** 数据范围编码：self / dept / center / custom_dept / company */
    private String scopeType;
    /** 数据范围中文名，前端直接展示，不必自己维护映射 */
    private String scopeLabel;
    /** custom_dept 时生效的部门集合 */
    private List<Long> scopeDeptIds;

    /** 已授予的权限点编码 */
    private List<String> permCodes;
    /** 已授予的权限点中文名，用于卡片上的标签展示 */
    private List<String> permNames;

    /** 拥有该角色的成员姓名 */
    private List<String> members;

    /** 1 内置角色（不可删除） */
    private Integer isBuiltin;
    /** 1 启用 0 停用 */
    private Integer status;
    /** 备注 */
    private String remark;
}
