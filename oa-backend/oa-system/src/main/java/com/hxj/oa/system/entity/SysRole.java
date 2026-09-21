package com.hxj.oa.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hxj.oa.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 角色 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_role")
public class SysRole extends BaseEntity {

    private Long companyId;
    /** 角色编码，如 ACCOUNTANT / DEPT_HEAD。流程指派规则按此编码解析 */
    private String code;
    private String name;
    private Long deptId;
    private String postName;
    private Integer isBuiltin;
    private Integer status;
    private String remark;
    private Long createdBy;
    private Long updatedBy;
}
