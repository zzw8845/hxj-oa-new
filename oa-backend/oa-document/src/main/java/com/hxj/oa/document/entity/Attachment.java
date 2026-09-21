package com.hxj.oa.document.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hxj.oa.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 附件 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("attachment")
public class Attachment extends BaseEntity {

    private Long companyId;
    private Long documentId;
    private String nodeKey;
    /** apply 申请 / approve 审批 / seal 用印 / receipt 付款回单 */
    private String bizType;
    private String fileName;
    /** 对象存储 Key（MinIO / OSS） */
    private String fileKey;
    private Long fileSize;
    private String mimeType;
    private Long uploaderId;
    private String uploaderName;
    private Long createdBy;
    private Long updatedBy;
}
