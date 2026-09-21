package com.hxj.oa.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hxj.oa.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 角色-权限关联 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("role_permission")
public class RolePermission extends BaseEntity {

    private Long roleId;
    private String permCode;
    private Long createdBy;
}
