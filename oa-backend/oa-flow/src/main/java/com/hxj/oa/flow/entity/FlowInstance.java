package com.hxj.oa.flow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hxj.oa.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 流程实例（Flowable 引擎状态的业务投影） */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("flow_instance")
public class FlowInstance extends BaseEntity {

    private Long documentId;
    private Long flowConfigId;
    /** 流程定义版本快照 */
    private Integer flowConfigVersion;
    /** 1 运行中 2 已完成 3 已终止 */
    private Integer status;
    private String currentNodeKey;

    /** Flowable 流程实例 ID —— 业务表与引擎 ACT_RU_* 表的唯一桥接键 */
    private String procInstId;
    /** Flowable 业务键（= document.doc_no） */
    private String businessKey;

    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
}
