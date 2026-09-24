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

    /** 公司 ID */
    private Long companyId;
    /** 单据 ID */
    private Long documentId;
    /** 流程实例 ID */
    private Long instanceId;
    /** flow_instance_node.id */
    private Long nodeId;
    /** 节点标识 */
    private String nodeKey;
    /** 节点名称 */
    private String nodeName;
    /** 引擎任务 ID */
    private String taskId;
    /** 原承办人 ID */
    private Long fromAssigneeId;
    /** 升级给谁；未找到上级时为空 */
    private Long toAssigneeId;
    /** 1 已升级 0 未找到上级负责人 */
    private Integer status;
    /** 说明 / 未升级原因 */
    private String reason;
    /** 创建人 ID */
    private Long createdBy;
    /** 最后更新人 ID */
    private Long updatedBy;
}
