package com.hxj.oa.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hxj.oa.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 角色管理范围（能【管】哪些部门的人的账号）。
 *
 * <p>⚠ 与 {@link RoleDataScope} 是<b>两个正交维度</b>，不要合并、也不要互相复用：
 * 前者答「能看多少数据」、本表答「能管哪些人」。详见
 * {@code com.hxj.oa.common.security.AdminScopeType} 与 sql/schema.sql 里本表的注释块。
 *
 * <p>一个角色一条记录，存在即更新（同 {@link RoleDataScope} 的写法）。
 * <b>没有记录 = 不授权</b>（{@code none}），不是「不限制」。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("role_admin_scope")
public class RoleAdminScope extends BaseEntity {

    private Long roleId;
    /** none=不能管任何人 / dept_subtree=本部门及下级 / all=全公司 */
    private String scopeType;
}
