package com.hxj.oa.document.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hxj.oa.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 用印申请（单据的业务扩展） */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("seal_apply")
public class SealApply extends BaseEntity {

    private Long documentId;
    private String sealProject;
    private Long sealDeptId;
    private LocalDateTime sealTime;
    private String fileName;
    /** 用章类型 公章 / 合同章 / 法人章 / 财务专用章 / 发票专用章 */
    private String sealType;
    private String sealReason;
    /** 0 待用印 1 已用印 2 已归还 —— 用印闭环的关键状态 */
    private Integer returnStatus;
    private LocalDateTime returnAt;
    private Long createdBy;
    private Long updatedBy;
}
