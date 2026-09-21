package com.hxj.oa.flow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hxj.oa.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 流程节点实例（审批记录 + 待办投影） */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("flow_instance_node")
public class FlowInstanceNode extends BaseEntity {

    private Long instanceId;
    private Long documentId;
    private String nodeKey;
    private String nodeName;
    private Integer nodeType;
    private Integer seqNo;
    /** 0 待处理 1 处理中 2 已通过 3 已驳回 4 已跳过 5 已抄送 6 已撤回 */
    private Integer status;
    private Long assigneeId;
    private String assigneeName;
    /** 候选处理人 ID 集合 JSON（会签/或签场景） */
    private String candidateIds;

    /** Flowable 任务 ID */
    private String taskId;
    /** 1 引擎投影 2 手工补录 */
    private Integer nodeSource;

    private String action;
    private String commentText;
    private String rejectLevel;
    private String materials;
    private LocalDateTime deadline;
    private LocalDateTime actionAt;
}
