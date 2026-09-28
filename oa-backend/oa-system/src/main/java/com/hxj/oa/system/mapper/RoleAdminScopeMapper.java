package com.hxj.oa.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hxj.oa.system.entity.RoleAdminScope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RoleAdminScopeMapper extends BaseMapper<RoleAdminScope> {

    /**
     * 当前用户各角色配置的管理范围编码（一个角色一行，由调用方取最宽者）。
     *
     * <p>与 {@code RoleDataScopeMapper#selectScopeTypesByUserId} 的过滤条件保持一致：
     * 只认未删除的关联、且角色本身未删除且已启用 —— 停用角色的范围不该继续生效。
     */
    @Select("""
            SELECT s.scope_type FROM role_admin_scope s
              JOIN user_role ur ON ur.role_id = s.role_id AND ur.deleted = 0
              JOIN sys_role  r  ON r.id = s.role_id AND r.deleted = 0 AND r.status = 1
            WHERE ur.user_id = #{userId} AND s.deleted = 0
            """)
    List<String> selectScopeTypesByUserId(@Param("userId") Long userId);
}
