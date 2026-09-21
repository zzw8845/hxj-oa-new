package com.hxj.oa.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hxj.oa.system.entity.Department;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DepartmentMapper extends BaseMapper<Department> {

    /** 本部门及全部下级（用物化路径一次定位，避免递归 CTE 的 MySQL 深度限制） */
    @Select("""
            SELECT id FROM department
            WHERE deleted = 0 AND company_id = #{companyId}
              AND (id = #{deptId} OR path LIKE CONCAT(#{path}, '%'))
            """)
    List<Long> selectSelfAndDescendantIds(@Param("companyId") Long companyId,
                                          @Param("deptId") Long deptId,
                                          @Param("path") String path);
}
