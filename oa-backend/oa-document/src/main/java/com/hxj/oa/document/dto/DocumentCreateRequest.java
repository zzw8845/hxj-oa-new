package com.hxj.oa.document.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 单据创建 / 保存草稿请求 */
@Data
public class DocumentCreateRequest {

    @NotNull(message = "单据类型不能为空")
    private Long docTypeId;

    /** 动态表单字段值 */
    private Map<String, Object> formData;

    /** 以下为可选冗余字段：不传则从 formData 按约定 key 自动抽取 */
    private String title;
    private BigDecimal amount;
    private String reason;
    private String invoiceSummary;

    /** 关联的前置单据 */
    private List<Long> linkedDocIds;

    private Integer needPostMaterial;
    /** 0 普通 1 紧急 */
    private Integer priority;
}
