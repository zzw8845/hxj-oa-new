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
    /** 权限点名称，如「查看全部表单」（展示用，中文不是判权依据） */
    private String name;
    /** 类型：1 菜单（组点+页面项，可否绑定看 parentCode——组点无父级不参与绑定）2 操作-写 3 操作-查询/审批；鉴权只看 code，类型仅驱动渲染分组 */
    private Integer permType;
    /** 父权限点编码，用于构建菜单树 */
    private String parentCode;
    /** 前端页面标识（仅菜单项有值）：前端据此挂载页面组件——「加一行=点亮一个已有页面」；页面代码本身仍随前端发版 */
    private String component;
    /** 同级排序号 */
    private Integer sortNo;
}
