package com.hxj.oa.flow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hxj.oa.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** 流程节点定义。node_key 即 BPMN 中节点元素的 id */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("flow_config_node")
public class FlowConfigNode extends BaseEntity {

    private Long flowConfigId;
    /** 节点标识（流程内唯一），如 n1 / gw1 */
    private String nodeKey;
    private String nodeName;
    /** 1 审批 2 抄送 3 条件网关 4 办理 5 发起 */
    private Integer nodeType;
    private Integer seqNo;
    /**
     * 条件网关的分支定义，JSON 数组：
     * [{"expr":"doc.amount >= 20000","target":"n4"},{"default":true,"target":"n5"}]
     */
    private String conditionExpr;
    private Integer allowCountersign;
    private Integer allowReject;
    /**
     * 该节点办理时是否必须上传凭证（0 否 / 1 是）。
     * 与 allowReject 同属「运行时规则」：审批动作那一刻读表判定，
     * 改它无需重新部署 BPMN，也不影响在途单据。
     */
    private Integer requireAttachment;
    /** 处理时限（小时），用于超时预警 */
    private BigDecimal slaHours;
}
