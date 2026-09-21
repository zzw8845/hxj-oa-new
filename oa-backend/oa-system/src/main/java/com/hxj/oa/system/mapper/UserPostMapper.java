package com.hxj.oa.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hxj.oa.system.entity.UserPost;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserPostMapper extends BaseMapper<UserPost> {

    /**
     * 物理删除某用户的兼岗记录（改岗位前先清空）。
     * 原因同 UserRoleMapper#physicalDeleteByUserId：唯一键含 deleted，逻辑删除会撞键。
     */
    @Delete("DELETE FROM user_post WHERE user_id = #{userId}")
    int physicalDeleteByUserId(@Param("userId") Long userId);
}
