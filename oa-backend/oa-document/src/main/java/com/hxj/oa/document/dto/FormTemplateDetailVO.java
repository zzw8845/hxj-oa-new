package com.hxj.oa.document.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/** 表单模板详情：模板本体（schema 已解析）+ 字段级权限 */
@Data
public class FormTemplateDetailVO {

    private Long id;
    private Long docTypeId;
    private String docTypeName;
    private String name;
    private Integer version;
    /** 0 草稿 1 生效 2 废弃 */
    private Integer status;
    private String statusText;
    /** 是否可编辑：只有草稿能改（生效版本要改必须新建版本，见 FormAdminService 的说明） */
    private Boolean editable;
    private Map<String, Object> schema;
    private List<Map<String, Object>> fieldPermissions;
}
