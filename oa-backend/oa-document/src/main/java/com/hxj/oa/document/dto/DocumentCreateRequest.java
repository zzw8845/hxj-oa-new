package com.hxj.oa.document.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 单据创建 / 保存草稿请求 */
@Data
public class DocumentCreateRequest {

    /** 单据类型 ID（取自 GET /api/document-types/enabled） */
    @NotNull(message = "单据类型不能为空")
    private Long docTypeId;

    /** 动态表单字段值（key 见 GET /api/forms/schema 返回的字段定义） */
    private Map<String, Object> formData;

    /** 以下为可选冗余字段：不传则从 formData 按约定 key 自动抽取 */
    /** 单据标题 */
    private String title;
    /** 金额（元）。⚠ 金额会影响流程条件分支的走向（如「大额走公司领导」） */
    private BigDecimal amount;
    /** 申请事由 */
    private String reason;
    /** 发票摘要 */
    private String invoiceSummary;

    /** 关联的前置单据 */
    private List<Long> linkedDocIds;

    /** 是否需要事后补料（1 需要 / 0 不需要）。⚠ 目前后端未消费该字段，传了不改变流程走向 */
    private Integer needPostMaterial;
    /** 0 普通 1 紧急 */
    private Integer priority;
}
