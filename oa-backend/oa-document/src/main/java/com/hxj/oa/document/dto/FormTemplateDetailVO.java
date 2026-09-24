package com.hxj.oa.document.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/** 表单模板详情：模板本体（schema 已解析）+ 字段级权限 */
@Data
public class FormTemplateDetailVO {

    /** 模板 ID */
    private Long id;
    /** 所属单据类型 ID */
    private Long docTypeId;
    /** 所属单据类型名称 */
    private String docTypeName;
    /** 模板名称 */
    private String name;
    /** 版本号，从 1 开始；生效版本要改必须新建版本 */
    private Integer version;
    /** 0 草稿 1 生效 2 废弃 */
    private Integer status;
    /** 状态中文名（后端下发，前端直接展示） */
    private String statusText;
    /** 是否可编辑：只有草稿能改（生效版本要改必须新建版本，见 FormAdminService 的说明） */
    private Boolean editable;
    /** 表单 Schema：{ docType, layout, fields:[{key,type,label,...}], rules:[{when,then}] } */
    private Map<String, Object> schema;
    /** 字段级权限：每项含 nodeKey（* 表示全部节点）、fieldKey、visible、editable */
    private List<Map<String, Object>> fieldPermissions;
}
