package com.hxj.oa.flow.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hxj.oa.flow.dto.AssigneeContext;
import com.hxj.oa.flow.entity.FlowConfigNode;
import com.hxj.oa.flow.entity.FlowInstanceNode;
import com.hxj.oa.flow.mapper.FlowConfigNodeMapper;
import com.hxj.oa.flow.mapper.FlowInstanceNodeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.delegate.TaskListener;
import org.flowable.task.service.delegate.DelegateTask;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 审批人解析监听器 —— BPMN 与「业务配置层」之间的桥。
 *
 * 每个 userTask 创建时触发：按 flow_node_assignee 规则解析出真正的处理人，
 * 同时把节点实例写入 flow_instance_node 作为待办投影。
 *
 * 之所以用 TaskListener 而不是在 BPMN 里写死 assignee：
 * 改规则只需改表，不必重新生成、重新部署流程。
 */
@Slf4j
@Component("assigneeTaskListener")
@RequiredArgsConstructor
public class AssigneeTaskListener implements TaskListener {

    private final AssigneeResolver assigneeResolver;
    private final FlowConfigNodeMapper nodeMapper;
    private final FlowInstanceNodeMapper instanceNodeMapper;

    @Override
    public void notify(DelegateTask task) {
        String nodeKey = task.getTaskDefinitionKey();
        Long flowConfigId = asLong(task.getVariable("flowConfigId"));
        Long flowInstanceId = asLong(task.getVariable("flowInstanceId"));
        Long documentId = asLong(task.getVariable("documentId"));

        FlowConfigNode node = nodeMapper.selectOne(Wrappers.<FlowConfigNode>lambdaQuery()
                .eq(FlowConfigNode::getFlowConfigId, flowConfigId)
                .eq(FlowConfigNode::getNodeKey, nodeKey)
                .last("LIMIT 1"));
        if (node == null) {
            log.error("流程节点定义缺失 flowConfigId={} nodeKey={}，任务将进入人工兜底状态", flowConfigId, nodeKey);
            return;
        }

        AssigneeContext ctx = AssigneeContext.builder()
                .companyId(asLong(task.getVariable("companyId")))
                .applicantId(asLong(task.getVariable("applicantId")))
                .applicantDeptId(asLong(task.getVariable("applicantDeptId")))
                .bizCategory(asString(task.getVariable("bizCategory")))
                .docTypeId(asLong(task.getVariable("docTypeId")))
                .amount(asBigDecimal(task.getVariable("amount")))
                .documentId(documentId)
                .docNo(asString(task.getVariable("docNo")))
                .formData(collectFormVars(task))
                .build();

        AssigneeResolver.ResolveResult result = assigneeResolver.resolve(node.getId(), ctx);

        // 1) 分派：单人直接指派，多人进候选池（或签/会签由 Flowable 原生能力处理）
        if (result.getCandidateIds().size() == 1) {
            task.setAssignee(String.valueOf(result.getCandidateIds().get(0)));
        } else {
            for (Long uid : result.getCandidateIds()) {
                task.addCandidateUser(String.valueOf(uid));
            }
        }
        task.setVariable("__hitRules_" + nodeKey, String.join(",", result.getHitRules()));
        /* 【自审标记】申请人就是该节点的审批人，已被 AssigneeResolver 剔除。
           这个标记后面会被 FlowRuntimeService#autoSkipUnassigned 读走，用来把
           「无匹配审批人」这句笼统留痕换成「申请人即审批人」—— 两者在界面上
           长得一样（都是 status=4 已跳过），但审计时要能回答"为什么这一环没了"。

           为什么必须显式：剔除申请人是**硬规则**，冷启动期（组织里只有一个人）
           必然触发。不留痕的话，客户看到的是"单据少了一环审批"，而系统里
           查不到任何原因 —— 这正是本项目最忌讳的「静默失败」。
           对照行业：泛微把「自动处理时在签字意见留痕」做成了独立开关，钉钉的
           去重也要求在流程记录里能看出"是被去重了"。 */
        if (result.isSelfSkipped()) {
            task.setVariable("__selfSkip_" + nodeKey, "1");
        }

        // 2) 待办投影：先落一条待处理记录，任务完成时由 FlowRuntimeService 更新
        FlowInstanceNode rec = new FlowInstanceNode();
        rec.setInstanceId(flowInstanceId);
        rec.setDocumentId(documentId);
        rec.setNodeKey(nodeKey);
        rec.setNodeName(node.getNodeName());
        rec.setNodeType(node.getNodeType());
        rec.setSeqNo(node.getSeqNo());
        rec.setStatus(0);
        rec.setTaskId(task.getId());
        rec.setNodeSource(1);
        rec.setCandidateIds(result.getCandidateIds().isEmpty() ? null
                : com.hxj.oa.common.util.JsonUtils.toJson(result.getCandidateIds()));
        rec.setAssigneeId(result.getCandidateIds().size() == 1 ? result.getCandidateIds().get(0) : null);
        if (node.getSlaHours() != null && node.getSlaHours().compareTo(BigDecimal.ZERO) > 0) {
            long minutes = node.getSlaHours().multiply(BigDecimal.valueOf(60)).longValue();
            rec.setDeadline(LocalDateTime.now().plusMinutes(minutes));
        }
        instanceNodeMapper.insert(rec);

        log.info("节点分派 nodeKey={} node={} candidates={} rules={} autoSkip={}",
                nodeKey, node.getNodeName(), result.getCandidateIds(), result.getHitRules(), result.isEmpty());
    }

    /** 把流程变量里的表单字段收集起来，供 condition 类规则判断 */
    private Map<String, Object> collectFormVars(DelegateTask task) {
        Map<String, Object> vars = new HashMap<>();
        Object raw = task.getVariable("formVarKeys");
        if (raw instanceof String keys && !keys.isBlank()) {
            for (String k : keys.split(",")) {
                String key = k.trim();
                if (!key.isEmpty()) {
                    Object v = task.getVariable(key);
                    if (v != null) {
                        vars.put(key, v);
                    }
                }
            }
        }
        return vars;
    }

    private Long asLong(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.valueOf(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private BigDecimal asBigDecimal(Object o) {
        if (o == null) {
            return BigDecimal.ZERO;
        }
        if (o instanceof BigDecimal b) {
            return b;
        }
        if (o instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(o));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}
