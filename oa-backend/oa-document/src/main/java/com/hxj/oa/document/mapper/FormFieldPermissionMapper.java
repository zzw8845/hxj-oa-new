package com.hxj.oa.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hxj.oa.document.entity.FormFieldPermission;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface FormFieldPermissionMapper extends BaseMapper<FormFieldPermission> {

    /**
     * 物理删除某模板的全部字段权限（全量覆盖前先清空）。
     *
     * <p>必须物理删：{@code uk_field_perm(template_id, node_key, field_key, deleted)}
     * 把 deleted 纳入了唯一键 ⇒ 逻辑删除的行只能存在一条，
     * "配一次 → 改一次 → 再配一次"就会在第三次撞唯一键。做法与
     * {@code RolePermissionMapper#physicalDeleteByRoleId} 一致。
     */
    @Delete("DELETE FROM form_field_permission WHERE template_id = #{templateId}")
    int physicalDeleteByTemplateId(@Param("templateId") Long templateId);
}
