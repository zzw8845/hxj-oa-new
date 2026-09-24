package com.hxj.oa.document.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 表单模板保存请求（新建 / 改草稿）。
 *
 * <p>schema 用 {@code Map} 而不是字符串接收：服务端必须**结构化校验**它
 * （字段 key 唯一、type 在白名单内、rules 引用的字段确实存在），
 * 拿到一个字符串就只能当黑盒存进去 —— 而 schema 是前端渲染与提交校验的共同依据，
 * 写坏了表现为"表单渲染崩"或"提交永远校验不过"，都属于很难反查的那一类。
 */
@Data
public class FormTemplateSaveReq {

    /** 所属单据类型 ID */
    @NotNull(message = "单据类型不能为空")
    private Long docTypeId;

    /** 模板名称 */
    @NotBlank(message = "模板名称不能为空")
    @Size(max = 64, message = "模板名称不能超过 64 字")
    private String name;

    /** 表单 Schema：{ docType, layout, fields:[{key,type,label,...}], rules:[{when,then}] } */
    @NotNull(message = "表单 Schema 不能为空")
    private Map<String, Object> schema;

    /** 字段级权限（可选，随模板一起存）。为空表示不动已有配置 */
    private List<FieldPerm> fieldPermissions;

    /** 字段级权限：控制「某个流程节点上、某个表单字段」是否可见 / 可编辑 */
    @Data
    public static class FieldPerm {
        /** 节点标识：* 表示所有节点，其余为流程节点 key（如 n3） */
        @NotBlank(message = "节点标识不能为空")
        private String nodeKey;

        /** 字段标识（对应 schema.fields[].key） */
        @NotBlank(message = "字段标识不能为空")
        private String fieldKey;

        /** 该节点是否可见 */
        private Boolean visible;

        /** 该节点是否可编辑 */
        private Boolean editable;
    }
}
