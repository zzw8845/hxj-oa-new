package com.hxj.oa.flow.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 流程预览：把配置链路 + 每个节点「实际会由谁审」一次算给前端 */
@Data
public class FlowPreviewVO {

    /** 流程配置 ID */
    private Long flowConfigId;
    /** 流程名称 */
    private String flowName;
    /** 流程定义 key（部署到引擎后的 procDefKey） */
    private String procDefKey;
    /** 流程配置版本号 */
    private Integer version;
    /** 部署状态：0 未部署 1 已部署 2 已废弃 */
    private Integer deployStatus;

    /** 节点列表（**完整配置链路**，条件分支的两侧目标都会返回，不按金额裁剪） */
    private List<NodePreview> nodes;

    /** 节点预览 */
    @Data
    public static class NodePreview {
        /** 节点编码，如 n1、gw1 */
        private String nodeKey;
        /** 节点名称，如「直属部门负责人」 */
        private String nodeName;
        /** 节点类型：1 审批 2 抄送 3 条件分支 4 办理 5 发起 */
        private Integer nodeType;
        /** 节点顺序号 */
        private Integer seqNo;
        /** 条件表达式（仅条件分支节点有值，如 doc.amount >= 20000） */
        private String conditionExpr;
        /** 该节点的处理时限（小时）；null = 无时限 */
        private BigDecimal slaHours;

        /** 解析出的审批人 */
        private List<Long> candidateIds;
        /** 解析出的审批人姓名（⚠ 没有 assignees 字段，那是另一件事：按 nodeId 索引的指派规则表） */
        private List<String> candidateNames;
        /** 命中的规则，便于排查「为什么是他审」 */
        private List<String> hitRules;
        /** 该节点在当前单据上下文中是否会被跳过 */
        private boolean selfSkipped;
        /** 是否压根解析不出人 */
        private boolean unresolved;
    }
}
