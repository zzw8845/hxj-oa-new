package com.hxj.oa.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hxj.oa.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 角色数据范围（行级权限）。JSON 列映射为 String，由 JsonColumn 解析 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("role_data_scope")
public class RoleDataScope extends BaseEntity {

    private Long roleId;
    /** self / dept / center / custom_dept / company */
    private String scopeType;
    /** JSON 数组，如 [1,2]，null = 不限制 */
    private String companyIds;
    /** JSON 数组，custom_dept 时使用 */
    private String deptIds;
}
