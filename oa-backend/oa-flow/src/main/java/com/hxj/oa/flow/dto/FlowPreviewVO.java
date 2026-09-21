package com.hxj.oa.flow.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 流程预览：把配置链路 + 每个节点「实际会由谁审」一次算给前端 */
@Data
public class FlowPreviewVO {

    private Long flowConfigId;
    private String flowName;
    private String procDefKey;
    private Integer version;
    private Integer deployStatus;

    private List<NodePreview> nodes;

    @Data
    public static class NodePreview {
        private String nodeKey;
        private String nodeName;
        private Integer nodeType;
        private Integer seqNo;
        private String conditionExpr;
        private BigDecimal slaHours;

        /** 解析出的审批人 */
        private List<Long> candidateIds;
        private List<String> candidateNames;
        /** 命中的规则，便于排查「为什么是他审」 */
        private List<String> hitRules;
        /** 该节点在当前单据上下文中是否会被跳过 */
        private boolean selfSkipped;
        /** 是否压根解析不出人 */
        private boolean unresolved;
    }
}
