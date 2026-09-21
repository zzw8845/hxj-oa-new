package com.hxj.oa.document.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hxj.oa.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 单据关联（前置单据 / 合同） */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("document_link")
public class DocumentLink extends BaseEntity {

    private Long documentId;
    private Long linkedId;
    /** prev_doc 前置单据 / contract 合同 */
    private String linkType;
    private Long createdBy;
}
