package com.hxj.oa.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hxj.oa.document.entity.FormTemplate;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface FormTemplateMapper extends BaseMapper<FormTemplate> {

    /**
     * 物理删除一个模板（**只用于草稿**）。
     *
     * <p>为什么草稿要物理删而不是逻辑删：{@code uk_form_tpl(doc_type_id, version, deleted)}
     * 把 deleted 纳入了唯一键，逻辑删除的行使"同一版本只能存在一条"⇒
     * "建 v3 → 删 v3 → 再建 v3 → 再删 v3"会在第二次删除时撞唯一键报 500。
     * 草稿从未生效、也**没有任何单据引用它**（单据只引用生效版本），所以物理删是安全的；
     * 生效/废弃版本则一律不允许删除，它们是"某张在途单据用的是哪版表单"的历史凭据。
     */
    @Delete("DELETE FROM form_template WHERE id = #{id}")
    int physicalDeleteById(@Param("id") Long id);
}
