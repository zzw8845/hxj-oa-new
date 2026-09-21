package com.hxj.oa.system.dto;

import lombok.Data;

import java.util.List;

/**
 * 角色视图对象。
 *
 * <p>相比 {@code SysRole} 补了三样前端配置页必须要的东西：
 * 权限点明细、数据范围、成员列表——否则「配置权限」弹窗只能显示占位文案。
 */
@Data
public class RoleVO {

    private Long id;
    private Long companyId;
    private String code;
    private String name;

    private Long deptId;
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
    private Integer status;
    private String remark;
}
