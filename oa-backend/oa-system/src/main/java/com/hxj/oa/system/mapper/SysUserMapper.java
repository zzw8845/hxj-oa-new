package com.hxj.oa.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hxj.oa.system.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
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

    /**
     * 查某部门负责人的用户 ID（节点指派 initiator_leader 规则）。
     *
     * <p><b>必须 join sys_user 校验可用性</b>：同文件的 {@link #selectUserIdsByRole} /
     * {@link #selectUserIdsByDeptAndRole} 都带 {@code u.status=1 AND u.deleted=0}，
     * 只有这一条漏了。后果分两层：
     * <ol>
     *   <li>leader 被停用 / 删除后照样被解析出来 —— 而 {@code AssigneeTaskListener} 只要
     *       {@code size()==1} 就 {@code setAssignee}，{@code autoSkipUnassigned} 又只判
     *       assignee 是否为空 ⇒ <b>"解析出个废人"比"解析不出人"更危险：非空废人静默永久卡死</b>
     *       （空列表反而有 auto_skip 兜底）。</li>
     *   <li>原条件 {@code d.leader_id IS NOT NULL} 放过了哨兵值 <b>0</b>（"未设负责人"）⇒
     *       返回 0 ⇒ {@code setAssignee("0")}。</li>
     * </ol>
     */
    @Select("""
            SELECT u.id FROM department d
              JOIN sys_user u ON u.id = d.leader_id AND u.deleted = 0 AND u.status = 1
            WHERE d.id = #{deptId} AND d.deleted = 0 AND d.leader_id > 0
            """)
    Long selectLeaderIdByDeptId(@Param("deptId") Long deptId);

    /**
     * 过滤出仍然可用的用户 ID（解析结果出口的防御纵深）。
     *
     * <p>放在出口而不是逐个规则里：新增一种指派规则时不会因为"忘了校验"而静默漏掉。
     */
    @Select({"<script>",
            "SELECT id FROM sys_user WHERE deleted = 0 AND status = 1 AND id IN",
            "<foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach>",
            "</script>"})
    List<Long> selectAliveIds(@Param("ids") Collection<Long> ids);
}
