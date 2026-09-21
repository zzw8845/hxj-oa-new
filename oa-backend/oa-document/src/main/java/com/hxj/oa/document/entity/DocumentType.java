package com.hxj.oa.document.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hxj.oa.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 单据类型 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("document_type")
public class DocumentType extends BaseEntity {

    private Long companyId;
    /** 类型编码，如 DAILY_PAYMENT */
    private String code;
    private String name;
    /** DAILY 日常付款 / BIZ 业务付款 / REIMBURSE 员工报销 / SEAL 用印申请 */
    private String category;
    private Long formTemplateId;
    private Long flowConfigId;
    private Integer mustLinkPrev;
    private Integer status;
    private Integer sortNo;
    private Long createdBy;
    private Long updatedBy;
}
