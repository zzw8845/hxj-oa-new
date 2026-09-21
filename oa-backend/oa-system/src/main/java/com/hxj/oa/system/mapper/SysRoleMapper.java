package com.hxj.oa.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hxj.oa.system.entity.SysRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {

    /** 按角色编码集合批量查角色（节点指派按角色解析时用） */
    @Select("""
            <script>
            SELECT * FROM sys_role
            WHERE deleted = 0 AND status = 1
              AND code IN
              <foreach collection="codes" item="c" open="(" separator="," close=")">#{c}</foreach>
            </script>
            """)
    List<SysRole> selectByCodes(@Param("codes") List<String> codes);
}
