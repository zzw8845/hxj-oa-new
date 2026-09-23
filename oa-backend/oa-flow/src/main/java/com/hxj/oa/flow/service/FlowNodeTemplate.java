package com.hxj.oa.flow.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hxj.oa.common.exception.BizException;
import com.hxj.oa.flow.entity.FlowConfigNode;
import com.hxj.oa.flow.entity.FlowNodeAssignee;
import com.hxj.oa.flow.mapper.FlowConfigNodeMapper;
import com.hxj.oa.flow.mapper.FlowNodeAssigneeMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 流程节点模板库 —— 把「中文节点名」翻译成「节点类型 + 指派规则」。
 *
 * <p>为什么必须有这一层：原型前端的审批节点是多选下拉（还允许自由输入），
 * 用户写的是「核算会计」「总经办」这种业务语言，而引擎需要的是严格的结构化定义。
 * 缺了这层翻译，随手输入的一个节点名会让流程<b>部署成功、却永远找不到审批人</b>——
 * 这是最难排查的一类故障：引擎没报错，单据就是卡着不动。
 *
 * <p>三级解析策略：
 * <ol>
 *   <li><b>精确匹配</b>预设模板（覆盖原型下拉里的全部候选）</li>
 *   <li><b>关键词兜底</b>（自定义输入时按"会计/负责人/办理"等语义猜测）</li>
 *   <li><b>兜底为空规则</b>：不猜了，留空让「流程预览」明确标出"未解析出处理人"，
 *       把问题暴露在配置阶段，而不是上线后</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlowNodeTemplate {

    public static final int TYPE_APPROVAL = 1;
    public static final int TYPE_CC = 2;
    public static final int TYPE_GATEWAY = 3;
    public static final int TYPE_HANDLE = 4;
    public static final int TYPE_START = 5;

    /** 哨兵：该节点的规则从库里既有同名节点学习（沿用已经配好的流程） */
    private static final String LEARN = "__LEARN__";

    private final FlowConfigNodeMapper nodeMapper;
    private final FlowNodeAssigneeMapper assigneeMapper;

    /** 节点模板：前端下拉直接用它渲染，每个候选都对应一条确定的规则 */
    @Data
    public static class NodeTemplate {
        private String name;
        private Integer nodeType;
        private String nodeTypeLabel;
        private String ruleType;
        private String ruleValue;
        /** 规则的人话说明 */
        private String ruleLabel;
        /** 处理时限（小时） */
        private Double slaHours;
        private Boolean allowCountersign;
        /** 是否必须上传办理凭证：办理类节点（出纳付款回单、用印盖章件）默认为真 */
        private Boolean requireAttachment;
        /**
         * 条件分支节点已解析好的分支（target 已是最终 nodeKey）。
         * 仅 {@code nodeType=3} 的节点会有值，其余节点为 null。
         */
        private List<Branch> branches;
    }

    /**
     * 已解析的分支：{@code target} 是最终 nodeKey（如 n4），可以直接写进 condition_expr。
     */
    @Data
    public static class Branch {
        /** 条件表达式；「否则」分支为空 */
        private String expr;
        private String target;
        private boolean defaultBranch;
    }

    private static final List<NodeTemplate> PRESETS = List.of(
            preset("发起人", TYPE_START, null, null, "发起节点：不生成审批任务", 0d, false),
            preset("部门负责人", TYPE_APPROVAL, "initiator_leader", "{\"fallbackRoleCode\":\"DEPT_HEAD\"}",
                    "取发起人所在部门的负责人；部门未设负责人时降级给部门负责人角色", 24d, true),
            preset("直属部门负责人", TYPE_APPROVAL, "initiator_leader", "{\"fallbackRoleCode\":\"DEPT_HEAD\"}",
                    "取发起人所在部门的负责人", 24d, true),
            preset("核算会计", TYPE_APPROVAL, "dept_role", "{\"roleCode\":\"ACCOUNTANT\",\"fallback\":\"company\"}",
                    "按发起人部门分派核算会计；本部门无该角色时降级到全公司", 24d, true),
            preset("会计（按部门）", TYPE_APPROVAL, "dept_role", "{\"roleCode\":\"ACCOUNTANT\",\"fallback\":\"company\"}",
                    "按发起人部门分派核算会计", 24d, true),
            preset("内控合规", TYPE_APPROVAL, "role", "{\"roleCode\":\"INTERNAL_CTRL\"}",
                    "内控合规岗", 24d, true),
            preset("内控合规/公司负责人", TYPE_APPROVAL, "role", "{\"roleCode\":\"INTERNAL_CTRL\"}",
                    "内控合规岗", 24d, true),
            preset("财务总监", TYPE_APPROVAL, "role", "{\"roleCode\":\"FIN_DIRECTOR\"}",
                    "财务总监", 48d, true),
            preset("公司领导", TYPE_APPROVAL, "role", "{\"roleCode\":\"GM\"}",
                    "公司领导（总经理）", 48d, true),
            preset("总经办", TYPE_APPROVAL, "role", "{\"roleCode\":\"GM\"}",
                    "公司领导（总经理）", 48d, true),
            preset("出纳", TYPE_HANDLE, "role", "{\"roleCode\":\"CASHIER\"}",
                    "出纳办理（办理节点：不阻塞审批链）", 24d, false),
            preset("出纳付款", TYPE_HANDLE, "role", "{\"roleCode\":\"CASHIER\"}",
                    "出纳办理付款", 24d, false),
            preset("发起人回传盖章文件", TYPE_HANDLE, "role", "{\"roleCode\":\"CASHIER\"}",
                    "出纳办理（回传盖章文件）", 24d, false),
            preset("用印办理", TYPE_HANDLE, LEARN, null,
                    "用印办理：沿用已有用印流程里配好的办理人", 24d, false),
            preset("公司章管理人", TYPE_HANDLE, LEARN, null,
                    "印章管理岗：沿用已有用印流程里配好的办理人", 24d, false)
    );

    /**
     * 关键词兜底表，顺序即优先级。
     * 办理类动词必须排在「发起」之前 —— 否则「发起人回传盖章文件」会被误判成发起节点。
     */
    private static final List<NodeTemplate> KEYWORD_FALLBACKS = List.of(
            preset("回传", TYPE_HANDLE, "role", "{\"roleCode\":\"CASHIER\"}", "出纳办理", 24d, false),
            preset("办理", TYPE_HANDLE, "role", "{\"roleCode\":\"CASHIER\"}", "出纳办理", 24d, false),
            preset("盖章", TYPE_HANDLE, "role", "{\"roleCode\":\"CASHIER\"}", "出纳办理", 24d, false),
            preset("付款", TYPE_HANDLE, "role", "{\"roleCode\":\"CASHIER\"}", "出纳办理", 24d, false),
            preset("出纳", TYPE_HANDLE, "role", "{\"roleCode\":\"CASHIER\"}", "出纳办理", 24d, false),
            preset("发起", TYPE_START, null, null, "发起节点", 0d, false),
            preset("财务总监", TYPE_APPROVAL, "role", "{\"roleCode\":\"FIN_DIRECTOR\"}", "财务总监", 48d, true),
            preset("会计", TYPE_APPROVAL, "dept_role", "{\"roleCode\":\"ACCOUNTANT\",\"fallback\":\"company\"}",
                    "按发起人部门分派核算会计", 24d, true),
            preset("内控", TYPE_APPROVAL, "role", "{\"roleCode\":\"INTERNAL_CTRL\"}", "内控合规岗", 24d, true),
            preset("领导", TYPE_APPROVAL, "role", "{\"roleCode\":\"GM\"}", "公司领导", 48d, true),
            preset("总经", TYPE_APPROVAL, "role", "{\"roleCode\":\"GM\"}", "公司领导", 48d, true),
            preset("负责人", TYPE_APPROVAL, "initiator_leader", "{\"fallbackRoleCode\":\"DEPT_HEAD\"}",
                    "取发起人所在部门的负责人", 24d, true),
            preset("章", TYPE_HANDLE, LEARN, null, "印章办理", 24d, false)
    );

    /** 供前端下拉渲染 */
    public List<NodeTemplate> templates() {
        return PRESETS.stream().map(this::deepCopy).toList();
    }

    /**
     * 条件分支节点规格：<b>没有审批人，只有分支定义</b>。
     *
     * <p>单独开一个工厂而不是走 {@link #resolve(String)}，是因为网关在模板库里没有预设
     * （它的名字是用户自由填的「金额分支」这类），走 resolve 会命中"未预设审批人规则"的
     * 兜底分支、白刷一条 warn 日志，还会把一个永远解析不出人的空规则带进库里。
     * 网关的正确语义就是"它不是任务节点"，所以在这里一次性说清楚。
     */
    public NodeTemplate gateway(String name) {
        NodeTemplate t = new NodeTemplate();
        t.setName(name);
        t.setNodeType(TYPE_GATEWAY);
        t.setNodeTypeLabel(nodeTypeLabel(TYPE_GATEWAY));
        t.setRuleType(null);
        t.setRuleValue(null);
        t.setRuleLabel("条件分支：按条件走不同节点，本身不产生审批任务");
        t.setSlaHours(0d);
        t.setAllowCountersign(false);
        t.setRequireAttachment(false);
        return t;
    }

    /**
     * 解析节点名 → 节点规格。
     * @throws BizException 名称为空时
     */
    public NodeTemplate resolve(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) {
            throw BizException.of("流程节点名称不能为空");
        }
        for (NodeTemplate t : PRESETS) {
            if (t.getName().equals(name)) {
                return withLearnedRule(t, name);
            }
        }
        for (NodeTemplate t : KEYWORD_FALLBACKS) {
            if (name.contains(t.getName())) {
                NodeTemplate copy = deepCopy(t);
                copy.setName(name);
                return withLearnedRule(copy, name);
            }
        }
        // 不猜了：留空规则，让流程预览把它明确标成「未解析出处理人」
        NodeTemplate t = preset(name, TYPE_APPROVAL, null, null,
                "未预设审批人规则，请在流程预览中确认后补配", 24d, true);
        log.warn("流程节点「{}」未匹配到任何预设模板，已生成无规则节点", name);
        return t;
    }

    /**
     * 处理 LEARN 哨兵：从库里已有的同名节点抄一条规则过来。
     * 这样「新增流程 → 选『用印办理』」能自动继承现有用印流程里配好的办理人，
     * 而不是硬编码一个 userId 到代码里。
     */
    private NodeTemplate withLearnedRule(NodeTemplate tpl, String name) {
        if (!LEARN.equals(tpl.getRuleType())) {
            return tpl;
        }
        NodeTemplate out = deepCopy(tpl);
        List<FlowConfigNode> sameName = nodeMapper.selectList(Wrappers.<FlowConfigNode>lambdaQuery()
                .eq(FlowConfigNode::getNodeName, name)
                .orderByAsc(FlowConfigNode::getId));
        for (FlowConfigNode n : sameName) {
            FlowNodeAssignee rule = assigneeMapper.selectOne(Wrappers.<FlowNodeAssignee>lambdaQuery()
                    .eq(FlowNodeAssignee::getNodeId, n.getId())
                    .orderByAsc(FlowNodeAssignee::getSortNo)
                    .last("LIMIT 1"));
            if (rule != null) {
                out.setRuleType(rule.getRuleType());
                out.setRuleValue(rule.getRuleValue());
                out.setRuleLabel("沿用已有「" + name + "」节点的配置");
                return out;
            }
        }
        out.setRuleType(null);
        out.setRuleValue(null);
        out.setRuleLabel("未找到可沿用的办理人配置，请在流程预览中确认后补配");
        log.warn("节点「{}」需要沿用既有配置，但库里没有同名节点的规则", name);
        return out;
    }

    private static NodeTemplate preset(String name, int nodeType, String ruleType, String ruleValue,
                                       String ruleLabel, Double slaHours, boolean allowCountersign) {
        NodeTemplate t = new NodeTemplate();
        t.setName(name);
        t.setNodeType(nodeType);
        t.setNodeTypeLabel(nodeTypeLabel(nodeType));
        t.setRuleType(ruleType);
        t.setRuleValue(ruleValue);
        t.setRuleLabel(ruleLabel);
        t.setSlaHours(slaHours);
        t.setAllowCountersign(allowCountersign);
        // 办理类节点（出纳付款、用印办理）几乎必然要回单或盖章件，
        // 默认强制上传凭证；审批类节点不强制，避免把普通审批卡死。
        t.setRequireAttachment(nodeType == TYPE_HANDLE);
        return t;
    }

    private static String nodeTypeLabel(int nodeType) {
        return switch (nodeType) {
            case TYPE_APPROVAL -> "审批";
            case TYPE_CC -> "抄送";
            case TYPE_GATEWAY -> "条件分支";
            case TYPE_HANDLE -> "办理";
            case TYPE_START -> "发起";
            default -> "未知";
        };
    }

    private NodeTemplate deepCopy(NodeTemplate src) {
        NodeTemplate t = new NodeTemplate();
        t.setName(src.getName());
        t.setNodeType(src.getNodeType());
        t.setNodeTypeLabel(src.getNodeTypeLabel());
        t.setRuleType(src.getRuleType());
        t.setRuleValue(src.getRuleValue());
        t.setRuleLabel(src.getRuleLabel());
        t.setSlaHours(src.getSlaHours());
        t.setAllowCountersign(src.getAllowCountersign());
        t.setRequireAttachment(src.getRequireAttachment());
        if (src.getBranches() != null) {
            // 深拷一份：模板库里的常量对象绝不能被调用方改到，否则一个流程的分支会串到另一条流程上
            List<Branch> branches = new ArrayList<>(src.getBranches().size());
            for (Branch b : src.getBranches()) {
                Branch c = new Branch();
                c.setExpr(b.getExpr());
                c.setTarget(b.getTarget());
                c.setDefaultBranch(b.isDefaultBranch());
                branches.add(c);
            }
            t.setBranches(branches);
        }
        return t;
    }
}
