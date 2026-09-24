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

    /** 流程名称（同一单据类型下按版本管理；已生效流程要改结构必须新建版本） */
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

    /** 一个流程节点的结构化定义（含条件分支时用 branches） */
    @Data
    public static class NodeItem {
        /** 节点名称，如「直属部门负责人」 */
        private String nodeName;
        /** 1 审批 2 抄送 3 条件分支 4 办理 5 发起 */
        private Integer nodeType;
        /** 指派规则（「谁是审批人」的唯一来源）：initiator_leader 发起人部门负责人 / dept_role 某部门某角色 / biztype_role 某业务类型某角色 / role 某角色 / user 指定人 / condition */
        private String ruleType;
        /** 规则参数（JSON 字符串，含义随 ruleType 变化） */
        private String ruleValue;
        /** 会签模式：1 或签（一人通过即可）2 会签（需全部通过） */
        private Integer signMode;
        /** 处理时限（小时），用于风险预警与超时升级；留空 = 无时限 */
        private Double slaHours;
        /** 该节点是否允许加签 */
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
