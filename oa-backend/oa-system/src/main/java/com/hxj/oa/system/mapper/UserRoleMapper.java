package com.hxj.oa.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hxj.oa.system.entity.UserRole;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface UserRoleMapper extends BaseMapper<UserRole> {

    /**
     * 物理删除某用户的全部角色绑定（重新分配角色前先清空）。
     *
     * <p>为什么不用逻辑删除：{@code uk_user_role(user_id, role_id, deleted)} 把 deleted
     * 纳入了唯一键，于是「同一角色解绑→再绑定→再解绑」时，
     * 第二条 deleted=1 的记录会与第一条撞唯一键。关联表本身不需要审计留痕，直接真删最稳。
     */
    @Delete("DELETE FROM user_role WHERE user_id = #{userId}")
    int physicalDeleteByUserId(@Param("userId") Long userId);

    /** 用户的角色编码集合 */
    @Select("""
            SELECT r.code FROM sys_role r
              JOIN user_role ur ON ur.role_id = r.id AND ur.deleted = 0
            WHERE ur.user_id = #{userId} AND r.deleted = 0 AND r.status = 1
            """)
    List<String> selectRoleCodesByUserId(@Param("userId") Long userId);
}
