package com.hxj.oa.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hxj.oa.system.entity.SysPermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysPermissionMapper extends BaseMapper<SysPermission> {

    /** 用户可见的权限点（按角色汇总，用于前端动态菜单） */
    @Select("""
            SELECT DISTINCT p.* FROM sys_permission p
              JOIN role_permission rp ON rp.perm_code = p.code AND rp.deleted = 0
              JOIN user_role ur       ON ur.role_id = rp.role_id AND ur.deleted = 0
              JOIN sys_role  r        ON r.id = rp.role_id AND r.deleted = 0 AND r.status = 1
            WHERE ur.user_id = #{userId} AND p.deleted = 0
            ORDER BY p.sort_no
            """)
    List<SysPermission> selectByUserId(@Param("userId") Long userId);
}
