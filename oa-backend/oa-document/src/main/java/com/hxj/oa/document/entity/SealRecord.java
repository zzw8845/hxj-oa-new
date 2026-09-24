package com.hxj.oa.document.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hxj.oa.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 用印台账（每次用印 / 归还动作） */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("seal_record")
public class SealRecord extends BaseEntity {

    /** 用印申请 ID */
    private Long sealApplyId;
    /** 单据 ID（冗余） */
    private Long documentId;
    /** use 用印 / return 归还 */
    private String action;
    /** 操作人 ID */
    private Long operatorId;
    /** 操作人姓名 */
    private String operatorName;
    /** 操作时间 */
    private LocalDateTime actionAt;
    /** 备注 */
    private String remark;
    /** 创建人 ID */
    private Long createdBy;
}
