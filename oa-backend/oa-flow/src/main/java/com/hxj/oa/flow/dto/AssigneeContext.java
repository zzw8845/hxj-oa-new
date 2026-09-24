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

    /** 公司 ID */
    private Long companyId;
    /** 申请人用户 ID */
    private Long applicantId;
    /** 申请人部门 ID（「发起人部门负责人」类规则依赖它） */
    private Long applicantDeptId;
    /** 业务大类 DAILY / BIZ / REIMBURSE / SEAL */
    private String bizCategory;
    /** 单据类型 ID（「某业务类型某角色」类规则依赖它） */
    private Long docTypeId;
    /** 金额（元），条件类规则会读它 */
    private BigDecimal amount;
    /** 单据 ID（可为空：预览时还没有真实单据） */
    private Long documentId;
    /** 单据编号（可为空） */
    private String docNo;
    /** 动态表单字段值，供 condition 类规则判断，如 {"sealType":"OFFICIAL"} */
    @Builder.Default
    private Map<String, Object> formData = new HashMap<>();

    public Object var(String key) {
        return formData == null ? null : formData.get(key);
    }
}
