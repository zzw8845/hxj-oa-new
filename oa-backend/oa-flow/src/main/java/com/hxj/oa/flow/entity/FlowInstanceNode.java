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

    /** 流程实例 ID */
    private Long instanceId;
    /** 单据 ID（冗余，便于直接按单据查审批记录） */
    private Long documentId;
    /** 节点标识（nodeKey），如 n1 / n2 */
    private String nodeKey;
    /** 节点名称，如「核算会计」 */
    private String nodeName;
    /** 节点类型 1 审批 2 抄送 3 条件网关 4 办理 5 发起 */
    private Integer nodeType;
    /** 节点顺序号，按流程顺序递增（前端按此排序展示审批时间线） */
    private Integer seqNo;
    /** 0 待处理 1 处理中 2 已通过 3 已驳回 4 已跳过 5 已抄送 6 已撤回 */
    private Integer status;
    /** 实际处理人 ID；未处理为 null */
    private Long assigneeId;
    /** 实际处理人姓名 */
    private String assigneeName;
    /** 候选处理人 ID 集合 JSON（会签/或签场景） */
    private String candidateIds;

    /** Flowable 任务 ID */
    private String taskId;
    /** 1 引擎投影 2 手工补录 */
    private Integer nodeSource;

    /** 处理动作 approve / reject / countersign / supplement / cc；未处理为 null */
    private String action;
    /** 审批意见（列名是 comment_text，因为 comment 是保留字） */
    private String commentText;
    /** 驳回目标层级（驳回动作才有值） */
    private String rejectLevel;
    /** 要求补充的材料说明 */
    private String materials;
    /** 本节点处理时限；未设置为 null */
    private LocalDateTime deadline;
    /** 处理时间；未处理为 null */
    private LocalDateTime actionAt;
}
