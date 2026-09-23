package com.hxj.oa.flow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 流程新增 / 修改请求。
 *
 * <p>两种提交形态：
 * <ul>
 *   <li><b>简化形态</b>：只给 {@code nodes}（节点名称数组），后端按节点模板库翻译成类型与指派规则。
 *       这是联调版原型当前用的方式。</li>
 *   <li><b>结构化形态</b>：给 {@code nodeItems}，每个节点显式带上 nodeType / ruleType / ruleValue；
 *       条件分支节点（nodeType=3）再带上 {@code branches}。
 *       这是流程编辑器的提交形态，优先级高于 nodes。</li>
 * </ul>
 */
@Data
public class FlowSaveReq {

    @NotBlank(message = "流程名称不能为空")
    @Size(max = 64, message = "流程名称不能超过 64 字")
    private String name;

    /** DAILY / BIZ / REIMBURSE / SEAL，也兼容前端传「日常审批」「业务单据」；留空则跟随单据类型 */
    private String category;

    /** 关联的单据类型（必填：flow_config.doc_type_id 非空，且流程定义 KEY 由它拼出） */
    private Long docTypeId;

    /** 节点名称列表，按审批顺序排列 */
    private List<String> nodes;

    /** 结构化节点定义，优先级高于 nodes */
    private List<NodeItem> nodeItems;

    @Data
    public static class NodeItem {
        private String nodeName;
        private Integer nodeType;
        private String ruleType;
        private String ruleValue;
        private Integer signMode;
        private Double slaHours;
        private Boolean allowCountersign;
        /** 该节点办理是否必须上传凭证；留空则按节点类型推默认（办理节点为真） */
        private Boolean requireAttachment;
        /**
         * 条件分支节点的分支定义，仅 {@code nodeType=3} 的节点可填。
         *
         * <p>为什么目标用「位置号」而不是 nodeKey：nodeKey 是服务端按顺序分配的
         * （{@code n1..nN}），客户端并不知道也不该猜；位置号就是本数组的下标 + 1，
         * 服务端据此翻译成最终 nodeKey。前端从库里读已有流程时，用
         * 「nodeKey → 行号」的映射把存量 {@code target} 还原成位置号，不要做算术。
         */
        private List<BranchItem> branches;
    }

    /** 条件分支的一条分支 */
    @Data
    public static class BranchItem {
        /**
         * 条件表达式，形如 {@code doc.amount >= 20000}。
         * 「否则」分支留空。语法受 {@code ConditionExprValidator} 白名单限制。
         */
        private String expr;
        /** 目标节点在 nodeItems 中的位置，<b>从 1 开始</b>；必须位于本网关之后、且不是发起节点 */
        private Integer target;
        /**
         * 是否「否则」（else）分支。只能有一条；
         * 一条都不给时服务端会自动补一条指向紧随其后的节点，保证 else 有去处。
         */
        private Boolean defaultBranch;
    }
}
