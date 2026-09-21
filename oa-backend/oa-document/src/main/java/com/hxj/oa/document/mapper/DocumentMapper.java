package com.hxj.oa.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hxj.oa.document.entity.Document;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface DocumentMapper extends BaseMapper<Document> {

    /** 待办统计等场景：按状态聚合数量 */
    @Select("""
            SELECT status, COUNT(*) AS cnt FROM document
            WHERE deleted = 0 AND company_id = #{companyId}
            GROUP BY status
            """)
    List<Map<String, Object>> countByStatus(@Param("companyId") Long companyId);

    /** 看板：按业务大类 + 月份聚合金额 */
    @Select("""
            SELECT business_category AS category,
                   DATE_FORMAT(submitted_at, '%Y-%m') AS ym,
                   COUNT(*) AS cnt,
                   IFNULL(SUM(amount), 0) AS total_amount
            FROM document
            WHERE deleted = 0 AND submitted_at IS NOT NULL
              AND company_id = #{companyId}
            GROUP BY business_category, ym
            ORDER BY ym DESC, category
            """)
    List<Map<String, Object>> dashboard(@Param("companyId") Long companyId);
}
