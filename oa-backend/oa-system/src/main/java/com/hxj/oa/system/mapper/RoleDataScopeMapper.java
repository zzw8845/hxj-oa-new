package com.hxj.oa.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hxj.oa.system.entity.RoleDataScope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RoleDataScopeMapper extends BaseMapper<RoleDataScope> {

    @Select("""
            SELECT ds.scope_type FROM role_data_scope ds
              JOIN user_role ur ON ur.role_id = ds.role_id AND ur.deleted = 0
              JOIN sys_role  r  ON r.id = ds.role_id AND r.deleted = 0 AND r.status = 1
            WHERE ur.user_id = #{userId} AND ds.deleted = 0
            """)
    List<String> selectScopeTypesByUserId(@Param("userId") Long userId);

    /** 返回 JSON 字符串数组，由 JsonColumn 解析 */
    @Select("""
            SELECT ds.dept_ids FROM role_data_scope ds
              JOIN user_role ur ON ur.role_id = ds.role_id AND ur.deleted = 0
              JOIN sys_role  r  ON r.id = ds.role_id AND r.deleted = 0 AND r.status = 1
            WHERE ur.user_id = #{userId} AND ds.deleted = 0 AND ds.dept_ids IS NOT NULL
            """)
    List<String> selectScopeDeptIdsByUserId(@Param("userId") Long userId);
}
