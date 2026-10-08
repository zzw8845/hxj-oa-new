package com.hxj.oa.document.dto;

import com.hxj.oa.flow.entity.FlowConfigNode;
import lombok.Data;

import java.util.List;

/**
 * 流程定义骨架（单据详情「全流程」视图的未来节点来源）。
 *
 * <p><b>为什么 detail 要额外给这份定义级数据</b>：flowHistory 只含已发生/在办的节点，
 * 员工打开单据看不到「这单接下来还要经过谁」——钉钉式的进度图需要先画出整条链，
 * 再把已发生的部分叠上去。而流程配置接口挂 {@code system:flow} 权限，普通员工拿不到，
 * 所以必须在详情里随单下发（只暴露展示字段，不含 allowReject/requireAttachment 等运行时规则）。
 */
@Data
public class FlowOutlineNode {

    /** 节点标识，与 flowHistory.nodeKey 同一命名空间，前端按它对齐两种数据 */
    private String nodeKey;
    private String nodeName;
    /** 1 审批 2 抄送 3 条件网关 4 办理 5 发起 */
    private Integer nodeType;
    private Integer seqNo;

    /**
     * 条件网关的分支去向（仅 nodeType=3 有值）：把 conditionExpr 的 JSON 还原成
     * 条件文本 + 目标 nodeKey，前端渲染「金额 ≥ 5000 → 公司领导 / 否则 → 出纳付款」。
     */
    private List<Branch> branches;

    /** 分支去向：条件原文（default 分支为 null）+ 目标节点 key */
    @Data
    public static class Branch {
        /** 条件表达式原文（如 doc.amount >= 20000）；默认分支为 null */
        private String expr;
        /** 是否默认分支（上面条件都不中时走这里） */
        private Boolean defaultBranch;
        /** 目标节点 key（前端在 outline 里查名字） */
        private String target;
    }

    public static FlowOutlineNode of(FlowConfigNode n, List<Branch> branches) {
        FlowOutlineNode o = new FlowOutlineNode();
        o.setNodeKey(n.getNodeKey());
        o.setNodeName(n.getNodeName());
        o.setNodeType(n.getNodeType());
        o.setSeqNo(n.getSeqNo());
        o.setBranches(branches);
        return o;
    }
}
