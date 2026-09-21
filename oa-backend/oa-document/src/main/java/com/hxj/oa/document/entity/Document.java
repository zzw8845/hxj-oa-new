package com.hxj.oa.document.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hxj.oa.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 单据主表。
 * 高频查询字段（doc_type_id / company_id / dept_id / status / amount）已从 JSON 中提为独立列，
 * 避免 MySQL 上对 JSON 做业务条件查询导致全表扫。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("document")
public class Document extends BaseEntity {

    private String docNo;
    private Long companyId;
    private Long docTypeId;
    /** DAILY / BIZ / REIMBURSE / SEAL */
    private String businessCategory;
    private String title;

    private Long applicantId;
    private String applicantName;
    private Long deptId;
    private String deptName;

    private BigDecimal amount;
    private String reason;
    private String invoiceSummary;

    /** 动态表单字段值 JSON（按 form_template.schema_json 渲染与校验） */
    private String formData;
    private Long formTemplateId;
    private Integer formTemplateVer;

    private Integer needPostMaterial;
    /** 0 无需 1 待补 2 已补 */
    private Integer postMaterialStatus;

    /** 0 草稿 1 待审批 2 审批中 3 已通过 4 已驳回 5 已撤回 6 已归档 */
    private Integer status;
    private String currentNodeKey;
    private String currentNodeName;
    private Long flowInstanceId;
    private Integer priority;
    private LocalDateTime deadline;
    private LocalDateTime submittedAt;
    private LocalDateTime closedAt;
    private Long createdBy;
    private Long updatedBy;
}
