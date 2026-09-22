package com.hxj.oa.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hxj.oa.document.entity.DocumentType;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DocumentTypeMapper extends BaseMapper<DocumentType> {

    /* 删除守卫统计：原生 SQL 显式带 deleted=0（@Select 不经过 MyBatis-Plus 逻辑删除拦截器）。
       三个统计分别对应三类引用方：单据本体、表单模板、审批流程 —— 任一存在即拒绝删除。 */

    @Select("SELECT COUNT(*) FROM document WHERE doc_type_id = #{docTypeId} AND deleted = 0")
    long countDocuments(Long docTypeId);

    @Select("SELECT COUNT(*) FROM form_template WHERE doc_type_id = #{docTypeId} AND deleted = 0")
    long countTemplates(Long docTypeId);

    @Select("SELECT COUNT(*) FROM flow_config WHERE doc_type_id = #{docTypeId} AND deleted = 0")
    long countFlowConfigs(Long docTypeId);
}
