package com.hxj.oa.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hxj.oa.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 权限点（功能权限）。code 为稳定英文标识，中文只做展示 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_permission")
public class SysPermission extends BaseEntity {

    /** 权限点编码，如 document:view:all */
    private String code;
    private String name;
    /** 1 菜单 2 按钮 3 接口 */
    private Integer permType;
    /** 父权限点编码，用于构建菜单树 */
    private String parentCode;
    private Integer sortNo;
}
