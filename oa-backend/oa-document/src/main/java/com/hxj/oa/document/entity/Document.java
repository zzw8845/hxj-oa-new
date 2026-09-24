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

    /** 单据编号（唯一），如 FK202601010001 */
    private String docNo;
    /** 所属公司 ID */
    private Long companyId;
    /** 单据类型 ID，对应 GET /api/documents/types 返回的 id */
    private Long docTypeId;
    /** DAILY / BIZ / REIMBURSE / SEAL */
    private String businessCategory;
    /** 申请事项 / 对应项目 */
    private String title;

    /** 申请人 ID */
    private Long applicantId;
    /** 申请人姓名（后端冗余，列表可直接展示） */
    private String applicantName;
    /** 申请部门 ID */
    private Long deptId;
    /** 申请部门名称（冗余） */
    private String deptName;

    /** 申请金额（元） */
    private BigDecimal amount;
    /** 申请事由 */
    private String reason;
    /** 简易发票明细 */
    private String invoiceSummary;

    /**
     * 动态表单字段值 JSON（按 form_template.schema_json 渲染与校验）。
     * ⚠ 这里是 **JSON 字符串**，不是对象，前端要 JSON.parse 一次。
     */
    private String formData;
    /** 表单模板 ID */
    private Long formTemplateId;
    /** 提交时快照的表单模板版本；之后改模板不影响在途单据 */
    private Integer formTemplateVer;

    /** ⚠ 是否需要付款后置材料 1 是 0 否。当前后端只落库、**无任何消费逻辑**，前端仅需回显 */
    private Integer needPostMaterial;
    /** 0 无需 1 待补 2 已补 */
    private Integer postMaterialStatus;

    /** 0 草稿 1 待审批 2 审批中 3 已通过 4 已驳回 5 已撤回 6 已归档。⚠ **2 表示在途，不是办结**；办结看 closedAt */
    private Integer status;
    /** 当前节点标识（nodeKey）；无在途流程时为 null */
    private String currentNodeKey;
    /** 当前节点名称，后端已翻译成中文，前端直接展示 */
    private String currentNodeName;
    /** 当前流程实例 ID；无在途流程时为 null */
    private Long flowInstanceId;
    /** 紧急度 0 普通 1 紧急 */
    private Integer priority;
    /** 时效截止时间（超时预警用）；未设置时限为 null */
    private LocalDateTime deadline;
    /** 提交时间；未提交为 null */
    private LocalDateTime submittedAt;
    /** **办结时间**（通过 / 驳回 / 撤回 / 归档时写入）；在途为 null */
    private LocalDateTime closedAt;
    /** 创建人 ID */
    private Long createdBy;
    /** 最后更新人 ID */
    private Long updatedBy;
}
