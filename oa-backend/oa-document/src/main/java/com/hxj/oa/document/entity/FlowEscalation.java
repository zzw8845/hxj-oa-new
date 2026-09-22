package com.hxj.oa.document.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hxj.oa.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 超时升级台账。
 *
 * <p>{@code uk_escalation_node(node_id, deleted)} 是幂等的**结构性保证**：
 * 一个节点最多一条升级记录，重复执行只会撞唯一键（代码把撞键当作"已升级过"），
 * 比"先查再插"可靠 —— 后者在定时任务与手动触发并发时会重复升级。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("flow_escalation")
public class FlowEscalation extends BaseEntity {

    private Long companyId;
    private Long documentId;
    private Long instanceId;
    /** flow_instance_node.id */
    private Long nodeId;
    private String nodeKey;
    private String nodeName;
    private String taskId;
    private Long fromAssigneeId;
    /** 升级给谁；未找到上级时为空 */
    private Long toAssigneeId;
    /** 1 已升级 0 未找到上级负责人 */
    private Integer status;
    private String reason;
    private Long createdBy;
    private Long updatedBy;
}
