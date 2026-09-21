package com.hxj.oa.flow.service;

import com.hxj.oa.common.util.JsonColumn;
import com.hxj.oa.flow.entity.FlowConfig;
import com.hxj.oa.flow.entity.FlowConfigNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * BPMN 2.0 生成器：把「业务配置层」的节点定义编译成 Flowable 可执行的 BPMN XML。
 *
 * 设计要点：
 * 1. 审批人**不写死在 BPMN 里**，而是统一挂一个 TaskListener，
 *    由它按 flow_node_assignee 规则在运行时解析 → 改规则只需改表，不必重新部署流程。
 * 2. 条件网关的分支来自 flow_config_node.condition_expr（JSON 数组）。
 * 3. 发起节点（node_type=5）不生成 BPMN 元素，它是 startEvent 的业务投影。
 */
@Slf4j
@Service
public class BpmnGenerator {

    private static final int TYPE_APPROVAL = 1;
    private static final int TYPE_CC = 2;
    private static final int TYPE_GATEWAY = 3;
    private static final int TYPE_HANDLE = 4;
    private static final int TYPE_START = 5;

    private static final String LISTENER_BEAN = "${assigneeTaskListener}";

    /**
     * @param config 流程定义（提供 procDefKey / name）
     * @param nodes  该流程的全部节点定义（含发起与网关）
     */
    public String generate(FlowConfig config, List<FlowConfigNode> nodes) {
        List<FlowConfigNode> sorted = new ArrayList<>(nodes);
        sorted.sort(Comparator.comparing(FlowConfigNode::getSeqNo));

        List<FlowConfigNode> flowNodes = sorted.stream()
                .filter(n -> n.getNodeType() != null && n.getNodeType() != TYPE_START)
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\"\n");
        sb.append("             xmlns:flowable=\"http://flowable.org/bpmn\"\n");
        sb.append("             xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        sb.append("             targetNamespace=\"http://hxj.com/oa\">\n");
        sb.append("  <process id=\"").append(esc(config.getProcDefKey()))
                .append("\" name=\"").append(esc(config.getName()))
                .append("\" isExecutable=\"true\">\n");

        sb.append("    <startEvent id=\"start\" name=\"开始\"/>\n");

        // ---- 节点元素 ----
        for (FlowConfigNode n : flowNodes) {
            sb.append(renderNode(n));
        }

        sb.append("    <endEvent id=\"end\" name=\"结束\"/>\n");

        // ---- 连线 ----
        sb.append(renderFlows(flowNodes));

        sb.append("  </process>\n");
        sb.append("</definitions>\n");
        return sb.toString();
    }

    private String renderNode(FlowConfigNode n) {
        String key = esc(n.getNodeKey());
        String name = esc(n.getNodeName());
        Integer type = n.getNodeType();
        StringBuilder sb = new StringBuilder();

        if (type == TYPE_GATEWAY) {
            sb.append("    <exclusiveGateway id=\"").append(key)
                    .append("\" name=\"").append(name).append("\"/>\n");
        } else if (type == TYPE_CC) {
            // 抄送：不阻塞流程，用 serviceTask 挂监听后直接通过
            sb.append("    <serviceTask id=\"").append(key)
                    .append("\" name=\"").append(name)
                    .append("\" flowable:delegateExpression=\"${ccTaskDelegate}\"/>\n");
        } else {
            // 审批 / 办理统一用 userTask，审批人由 TaskListener 运行时解析
            sb.append("    <userTask id=\"").append(key)
                    .append("\" name=\"").append(name).append("\"");
            sb.append(" flowable:formKey=\"node:").append(key).append("\"");
            sb.append(">\n");
            sb.append("      <extensionElements>\n");
            sb.append("        <flowable:taskListener event=\"create\" delegateExpression=\"")
                    .append(LISTENER_BEAN).append("\"/>\n");
            sb.append("      </extensionElements>\n");
            sb.append("    </userTask>\n");
        }
        return sb.toString();
    }

    /**
     * 顺序连边 + 网关分支。
     * 网关的下一个节点由分支边给出入边，不再额外连顺序边，避免重复入边。
     */
    private String renderFlows(List<FlowConfigNode> nodes) {
        StringBuilder sb = new StringBuilder();
        String prev = "start";
        int idx = 0;
        int flowSeq = 0;

        while (idx < nodes.size()) {
            FlowConfigNode node = nodes.get(idx);
            String key = node.getNodeKey();

            if (node.getNodeType() == TYPE_GATEWAY) {
                if (prev != null) {
                    sb.append(flow("f_" + flowSeq++, prev, key, null));
                }
                List<Map<String, Object>> branches = JsonColumn.toList(node.getConditionExpr());
                String nextKey = (idx + 1 < nodes.size()) ? nodes.get(idx + 1).getNodeKey() : "end";
                boolean hasDefault = branches.stream()
                        .anyMatch(b -> Boolean.TRUE.equals(JsonColumn.toBool(b, "default")));

                for (Map<String, Object> b : branches) {
                    String target = JsonColumn.str(b, "target");
                    if (target == null || target.isBlank()) {
                        target = nextKey;
                    }
                    boolean isDefault = Boolean.TRUE.equals(JsonColumn.toBool(b, "default"));
                    String expr = isDefault ? null : JsonColumn.str(b, "expr");
                    sb.append(flow("f_" + flowSeq++, key, target, expr));
                }
                // 没有显式 default 分支时，补一条到下一节点，防止流程卡死
                if (!hasDefault && !"end".equals(nextKey)) {
                    sb.append(flow("f_" + flowSeq++, key, nextKey, null));
                }
                // 下一节点的入边已由分支给出
                prev = null;
                idx++;
                continue;
            }

            if (prev != null) {
                sb.append(flow("f_" + flowSeq++, prev, key, null));
            }
            prev = key;
            idx++;
        }

        if (prev != null) {
            sb.append(flow("f_" + flowSeq, prev, "end", null));
        }
        return sb.toString();
    }

    private String flow(String id, String from, String to, String conditionExpr) {
        StringBuilder sb = new StringBuilder();
        sb.append("    <sequenceFlow id=\"").append(id)
                .append("\" sourceRef=\"").append(esc(from))
                .append("\" targetRef=\"").append(esc(to)).append("\"");
        if (conditionExpr == null || conditionExpr.isBlank()) {
            sb.append("/>\n");
        } else {
            sb.append(">\n");
            sb.append("      <conditionExpression xsi:type=\"tFormalExpression\"><![CDATA[")
                    .append(toUel(conditionExpr))
                    .append("]]></conditionExpression>\n");
            sb.append("    </sequenceFlow>\n");
        }
        return sb.toString();
    }

    /**
     * 把配置里写的 `doc.amount >= 20000` 转成 Flowable 的 UEL `${amount >= 20000}`。
     * 业务表里保留带 doc. 前缀的可读写法，运行时统一拍平成顶层变量。
     */
    private String toUel(String expr) {
        String cleaned = expr.replace("doc.", "");
        // 字符串字面量保持引号；已在 SQL 种子中写成 'OFFICIAL' 形式
        return "${" + cleaned + "}";
    }

    private String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
