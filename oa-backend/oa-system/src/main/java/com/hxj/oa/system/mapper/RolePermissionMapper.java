package com.hxj.oa.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hxj.oa.system.entity.RolePermission;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RolePermissionMapper extends BaseMapper<RolePermission> {

    /**
     * 物理删除某角色的全部权限点（全量覆盖前先清空）。
     * 原因同 {@code UserRoleMapper#physicalDeleteByUserId}：
     * uk_role_perm(role_id, perm_code, deleted) 含 deleted，逻辑删除会累积撞键。
     */
    @Delete("DELETE FROM role_permission WHERE role_id = #{roleId}")
    int physicalDeleteByRoleId(@Param("roleId") Long roleId);

    /** 用户经角色汇总出的权限点编码集合 */
    @Select("""
            SELECT DISTINCT rp.perm_code FROM role_permission rp
              JOIN user_role ur ON ur.role_id = rp.role_id AND ur.deleted = 0
              JOIN sys_role  r  ON r.id = rp.role_id AND r.deleted = 0 AND r.status = 1
            WHERE ur.user_id = #{userId} AND rp.deleted = 0
            """)
    List<String> selectPermCodesByUserId(@Param("userId") Long userId);
}
