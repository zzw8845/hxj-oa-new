package com.hxj.oa.flow.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/** 发起流程所需的业务事实（由单据模块组装，flow 模块不反向依赖单据模块） */
@Data
@Builder
public class FlowStartRequest {

    private Long documentId;
    private String docNo;
    private Long companyId;
    private Long applicantId;
    private Long applicantDeptId;
    /** DAILY / BIZ / REIMBURSE / SEAL */
    private String bizCategory;
    private Long docTypeId;
    private BigDecimal amount;
    /** 动态表单字段值：会平铺为流程变量，供条件表达式与 condition 类规则使用 */
    @Builder.Default
    private Map<String, Object> formData = new HashMap<>();
}
