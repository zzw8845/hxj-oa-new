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

    /** 单据 ID（流程实例通过它反查业务单据） */
    private Long documentId;
    /** 单据编号 */
    private String docNo;
    /** 公司 ID */
    private Long companyId;
    /** 申请人用户 ID */
    private Long applicantId;
    /** 申请人部门 ID（「发起人部门负责人」类规则依赖它） */
    private Long applicantDeptId;
    /** DAILY / BIZ / REIMBURSE / SEAL */
    private String bizCategory;
    /** 单据类型 ID */
    private Long docTypeId;
    /** 金额（元），条件分支表达式会读它 */
    private BigDecimal amount;
    /** 动态表单字段值：会平铺为流程变量，供条件表达式与 condition 类规则使用 */
    @Builder.Default
    private Map<String, Object> formData = new HashMap<>();
}
