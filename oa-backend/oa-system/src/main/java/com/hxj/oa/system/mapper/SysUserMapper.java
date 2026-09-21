package com.hxj.oa.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hxj.oa.system.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /** 按「部门 + 角色编码」查在职用户（节点指派 dept_role 规则的核心查询） */
    @Select("""
            SELECT u.id FROM sys_user u
              JOIN user_role ur ON ur.user_id = u.id AND ur.deleted = 0
              JOIN sys_role  r  ON r.id = ur.role_id AND r.deleted = 0
            WHERE r.code = #{roleCode} AND u.dept_id = #{deptId}
              AND u.deleted = 0 AND u.status = 1 AND r.status = 1
            """)
    List<Long> selectUserIdsByDeptAndRole(@Param("deptId") Long deptId, @Param("roleCode") String roleCode);

    /** 按角色编码查在职用户（不限部门，或指定部门） */
    @Select("""
            <script>
            SELECT u.id FROM sys_user u
              JOIN user_role ur ON ur.user_id = u.id AND ur.deleted = 0
              JOIN sys_role  r  ON r.id = ur.role_id AND r.deleted = 0
            WHERE r.code = #{roleCode} AND u.deleted = 0 AND u.status = 1 AND r.status = 1
            <if test="companyId != null"> AND u.company_id = #{companyId} </if>
            </script>
            """)
    List<Long> selectUserIdsByRole(@Param("roleCode") String roleCode, @Param("companyId") Long companyId);

    /** 查某部门负责人的用户 ID（节点指派 initiator_leader 规则） */
    @Select("""
            SELECT d.leader_id FROM department d
            WHERE d.id = #{deptId} AND d.deleted = 0 AND d.leader_id IS NOT NULL
            """)
    Long selectLeaderIdByDeptId(@Param("deptId") Long deptId);
}
