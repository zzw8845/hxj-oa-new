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

    /** 所属公司 ID */
    private Long companyId;
    /** 角色编码，如 ACCOUNTANT / DEPT_HEAD。流程指派规则按此编码解析 */
    private String code;
    /** 角色名称，如 核算会计 */
    private String name;
    /** 归属部门 ID，可空（表示不限部门） */
    private Long deptId;
    /** 对应岗位名称（展示用） */
    private String postName;
    /** 是否内置角色 1 是 0 否；内置角色不可删 */
    private Integer isBuiltin;
    /** 状态 1 启用 0 停用 */
    private Integer status;
    /** 备注 */
    private String remark;
    /** 创建人 ID */
    private Long createdBy;
    /** 最后更新人 ID */
    private Long updatedBy;
}
