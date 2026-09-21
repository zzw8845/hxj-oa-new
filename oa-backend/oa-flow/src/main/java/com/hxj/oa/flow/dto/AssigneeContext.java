package com.hxj.oa.flow.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 指派解析上下文：单据提交时的业务事实。
 * 节点指派规则靠这个上下文解析出「谁来审」，而不是靠硬编码角色名。
 */
@Data
@Builder
public class AssigneeContext {

    private Long companyId;
    private Long applicantId;
    private Long applicantDeptId;
    /** 业务大类 DAILY / BIZ / REIMBURSE / SEAL */
    private String bizCategory;
    private Long docTypeId;
    private BigDecimal amount;
    private Long documentId;
    private String docNo;
    /** 动态表单字段值，供 condition 类规则判断，如 {"sealType":"OFFICIAL"} */
    @Builder.Default
    private Map<String, Object> formData = new HashMap<>();

    public Object var(String key) {
        return formData == null ? null : formData.get(key);
    }
}
