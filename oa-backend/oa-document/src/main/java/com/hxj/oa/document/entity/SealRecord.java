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

    private Long sealApplyId;
    private Long documentId;
    /** use 用印 / return 归还 */
    private String action;
    private Long operatorId;
    private String operatorName;
    private LocalDateTime actionAt;
    private String remark;
    private Long createdBy;
}
