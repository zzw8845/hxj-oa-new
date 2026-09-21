package com.hxj.oa.flow.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hxj.oa.common.exception.BizException;
import com.hxj.oa.common.security.UserContext;
import com.hxj.oa.flow.dto.FlowSaveReq;
import com.hxj.oa.flow.entity.FlowConfig;
import com.hxj.oa.flow.entity.FlowConfigNode;
import com.hxj.oa.flow.entity.FlowInstance;
import com.hxj.oa.flow.entity.FlowNodeAssignee;
import com.hxj.oa.flow.mapper.FlowConfigMapper;
import com.hxj.oa.flow.mapper.FlowConfigNodeMapper;
import com.hxj.oa.flow.mapper.FlowInstanceMapper;
import com.hxj.oa.flow.mapper.FlowNodeAssigneeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 流程配置管理（写侧）：新增流程、修改流程、调整个节点审批人、废弃流程。
 *
 * <h3>核心约束：改结构必须新建版本，绝不原地改节点</h3>
 * 运行时的 {@code AssigneeTaskListener} 是按 <b>(flowConfigId, nodeKey)</b> 定位节点定义的，
 * 而 flowConfigId 在流程实例启动时就写进了流程变量。这意味着：
 * <ul>
 *   <li><b>原地修改某版本的节点</b>（删了重建）→ 在途单据的 flowConfigId 没变、但节点没了
 *       → 监听器查不到节点 → 任务永远解析不出处理人，单据静默卡死。</li>
 *   <li><b>新建版本记录</b>（本类的做法）→ 在途单据继续引用旧 flowConfigId，
 *       节点定义原封不动，流程照常走完；新提交的单据走新版本。</li>
 * </ul>
 * 这正是 {@code flow_config} 表把 version 纳入唯一键、并在 {@code document_type}
 * 上留 flow_config_id 指针的设计意图。
 *
 * <h3>唯一例外：只改审批人</h3>
 * 调整某节点的指派规则<b>不需要</b>新建版本，因为规则是运行时按 nodeId 查表解析的
 * （见 {@code AssigneeResolver}）。改表即生效，在途单据尚未到达的节点会按新规则解析——
 * 这正是"改规则不必重新部署 BPMN"的设计初衷。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlowConfigAdminService {

    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_RETIRED = 2;
    private static final int INSTANCE_RUNNING = 1;

    private final FlowConfigMapper configMapper;
    private final FlowConfigNodeMapper nodeMapper;
    private final FlowNodeAssigneeMapper assigneeMapper;
    private final FlowInstanceMapper instanceMapper;
    private final FlowDeployService flowDeployService;
    private final FlowNodeTemplate nodeTemplate;

    /* ------------------------------------------------------------------ 读 */

    /** 节点模板库，供前端「审批节点」下拉渲染 */
    public List<FlowNodeTemplate.NodeTemplate> templates() {
        return nodeTemplate.templates();
    }

    /* ------------------------------------------------------------------ 写 */

    /** 新增流程：同一单据类型若已有生效版本，旧版本自动废弃 */
    @Transactional(rollbackFor = Exception.class)
    public FlowConfig create(FlowSaveReq req) {
        return createVersion(req, requireDocType(req.getDocTypeId()));
    }

    /**
     * 修改流程：走与新增完全相同的路径——生成新版本并部署。
     * 传进来的 id 只用于取默认值（单据类型、名称），旧版本记录本身只被置为「废弃」，
     * 其节点定义保持不动，因为在途单据还在引用它。
     */
    @Transactional(rollbackFor = Exception.class)
    public FlowConfig update(Long id, FlowSaveReq req) {
        FlowConfig old = requireConfig(id);
        Long docTypeId = req.getDocTypeId() != null ? requireDocType(req.getDocTypeId()) : old.getDocTypeId();
        if (!StringUtils.hasText(req.getName())) {
            req.setName(old.getName());
        }
        if (req.getNodes() == null && req.getNodeItems() == null) {
            // 只改了名称/分类，节点沿用当前版本的
            req.setNodes(nodeMapper.selectList(Wrappers.<FlowConfigNode>lambdaQuery()
                            .eq(FlowConfigNode::getFlowConfigId, id)
                            .orderByAsc(FlowConfigNode::getSeqNo))
                    .stream().map(FlowConfigNode::getNodeName).toList());
        }
        return createVersion(req, docTypeId);
    }

    /**
     * 调整节点审批人（不新建版本，直接改规则表）。
     *
     * <p>之所以安全：规则是运行时按 nodeId 查的，改完立即对「尚未到达该节点」的单据生效，
     * 不需要重新部署 BPMN。这也是把审批人从 BPMN 里拆出来挂 TaskListener 的收益。
     */
    @Transactional(rollbackFor = Exception.class)
    public FlowConfig updateAssignees(Long flowConfigId, List<NodeAssignReq> requests) {
        requireConfig(flowConfigId);
        if (requests == null || requests.isEmpty()) {
            throw BizException.of("没有需要更新的节点审批人");
        }
        List<FlowConfigNode> nodes = nodeMapper.selectList(Wrappers.<FlowConfigNode>lambdaQuery()
                .eq(FlowConfigNode::getFlowConfigId, flowConfigId));
        Map<String, Long> idByKey = nodes.stream()
                .collect(Collectors.toMap(FlowConfigNode::getNodeKey, FlowConfigNode::getId, (a, b) -> a));
        Map<String, Long> idByName = nodes.stream()
                .collect(Collectors.toMap(FlowConfigNode::getNodeName, FlowConfigNode::getId, (a, b) -> a));

        for (NodeAssignReq r : requests) {
            Long nodeId = r.getNodeId();
            if (nodeId == null && StringUtils.hasText(r.getNodeKey())) {
                nodeId = idByKey.get(r.getNodeKey().trim());
            }
            if (nodeId == null && StringUtils.hasText(r.getNodeName())) {
                nodeId = idByName.get(r.getNodeName().trim());
            }
            if (nodeId == null) {
                throw BizException.of("流程中不存在节点：%s",
                        StringUtils.hasText(r.getNodeName()) ? r.getNodeName() : r.getNodeKey());
            }
            // 清掉旧规则再写新的（全量覆盖语义）
            assigneeMapper.delete(Wrappers.<FlowNodeAssignee>lambdaQuery()
                    .eq(FlowNodeAssignee::getNodeId, nodeId));
            if (r.getRules() != null) {
                int sort = 0;
                for (RuleItem item : r.getRules()) {
                    if (!StringUtils.hasText(item.getRuleType())) {
                        continue;
                    }
                    FlowNodeAssignee rule = new FlowNodeAssignee();
                    rule.setNodeId(nodeId);
                    rule.setRuleType(item.getRuleType().trim());
                    rule.setRuleValue(item.getRuleValue());
                    rule.setSignMode(item.getSignMode() == null ? 1 : item.getSignMode());
                    rule.setSortNo(sort++);
                    assigneeMapper.insert(rule);
                }
            }
        }
        log.info("调整流程节点审批人 flowConfigId={} 节点数={} 操作人={}",
                flowConfigId, requests.size(), UserContext.currentUserId());
        return configMapper.selectById(flowConfigId);
    }

    /** 废弃流程（有在途单据时拒绝，避免把正在跑的流程下线） */
    @Transactional(rollbackFor = Exception.class)
    public void retire(Long id) {
        FlowConfig cfg = requireConfig(id);
        long running = instanceMapper.selectCount(Wrappers.<FlowInstance>lambdaQuery()
                .eq(FlowInstance::getFlowConfigId, id)
                .eq(FlowInstance::getStatus, INSTANCE_RUNNING));
        if (running > 0) {
            throw BizException.of("流程「%s」还有 %d 张单据在审批中，不能废弃", cfg.getName(), running);
        }
        FlowConfig upd = new FlowConfig();
        upd.setId(id);
        upd.setStatus(STATUS_RETIRED);
        upd.setUpdatedBy(UserContext.currentUserId());
        configMapper.updateById(upd);
        log.info("废弃流程 id={} name={} 操作人={}", id, cfg.getName(), UserContext.currentUserId());
    }

    /* ------------------------------------------------------------------ 内部 */

    private FlowConfig createVersion(FlowSaveReq req, Long docTypeId) {
        List<FlowNodeTemplate.NodeTemplate> specs = toSpecs(req);
        assertHasExecutableNode(specs);

        Long companyId = UserContext.currentCompanyId();
        int version = nextVersion(docTypeId);
        String procDefKey = buildProcDefKey(docTypeId, version);

        // 旧版本先下线（其节点定义保持不动 —— 在途单据还在用）
        retireActive(docTypeId);

        FlowConfig cfg = new FlowConfig();
        cfg.setCompanyId(companyId);
        cfg.setDocTypeId(docTypeId);
        cfg.setName(req.getName().trim());
        cfg.setCategory(resolveCategory(req.getCategory(), docTypeId));
        cfg.setVersion(version);
        cfg.setStatus(STATUS_ACTIVE);
        cfg.setEffectiveFrom(LocalDateTime.now());
        cfg.setEngineType("flowable");
        cfg.setProcDefKey(procDefKey);
        cfg.setDeployStatus(0);
        cfg.setCreatedBy(UserContext.currentUserId());
        configMapper.insert(cfg);

        writeNodes(cfg.getId(), specs);
        // 让后续新提交的单据走这条新链路
        configMapper.updateDocTypeFlowConfig(docTypeId, cfg.getId());

        FlowConfig deployed = flowDeployService.deploy(cfg.getId());
        log.info("流程配置已保存并部署 id={} name={} docTypeId={} version={} procDefKey={} deployStatus={}",
                deployed.getId(), deployed.getName(), docTypeId, version, procDefKey, deployed.getDeployStatus());
        return deployed;
    }

    /** 节点规格：结构化 nodeItems 优先，否则按名称走模板库翻译 */
    private List<FlowNodeTemplate.NodeTemplate> toSpecs(FlowSaveReq req) {
        if (req.getNodeItems() != null && !req.getNodeItems().isEmpty()) {
            List<FlowNodeTemplate.NodeTemplate> out = new ArrayList<>();
            for (FlowSaveReq.NodeItem item : req.getNodeItems()) {
                if (item == null || !StringUtils.hasText(item.getNodeName())) {
                    continue;
                }
                FlowNodeTemplate.NodeTemplate t = nodeTemplate.resolve(item.getNodeName());
                if (item.getNodeType() != null) {
                    t.setNodeType(item.getNodeType());
                }
                if (StringUtils.hasText(item.getRuleType())) {
                    t.setRuleType(item.getRuleType());
                    t.setRuleValue(item.getRuleValue());
                }
                if (item.getSlaHours() != null) {
                    t.setSlaHours(item.getSlaHours());
                }
                if (item.getAllowCountersign() != null) {
                    t.setAllowCountersign(item.getAllowCountersign());
                }
                if (item.getRequireAttachment() != null) {
                    t.setRequireAttachment(item.getRequireAttachment());
                }
                out.add(t);
            }
            return out;
        }
        if (req.getNodes() == null || req.getNodes().isEmpty()) {
            throw BizException.of("审批节点不能为空，至少需要一个审批或办理节点");
        }
        return req.getNodes().stream()
                .filter(StringUtils::hasText)
                .map(nodeTemplate::resolve)
                .toList();
    }

    /** 全是发起节点的流程没有意义 —— BPMN 生成出来会是 start → end */
    private void assertHasExecutableNode(List<FlowNodeTemplate.NodeTemplate> specs) {
        boolean hasExecutable = specs.stream()
                .anyMatch(s -> s.getNodeType() != null && s.getNodeType() != FlowNodeTemplate.TYPE_START);
        if (!hasExecutable) {
            throw BizException.of("流程至少要有一个审批或办理节点（当前只配置了发起节点）");
        }
    }

    private void writeNodes(Long flowConfigId, List<FlowNodeTemplate.NodeTemplate> specs) {
        int seq = 0;
        for (FlowNodeTemplate.NodeTemplate s : specs) {
            seq++;
            FlowConfigNode n = new FlowConfigNode();
            n.setFlowConfigId(flowConfigId);
            n.setNodeKey("n" + seq);
            n.setNodeName(s.getName());
            n.setNodeType(s.getNodeType() == null ? FlowNodeTemplate.TYPE_APPROVAL : s.getNodeType());
            n.setSeqNo(seq);
            n.setAllowCountersign(Boolean.TRUE.equals(s.getAllowCountersign()) ? 1 : 0);
            n.setAllowReject(1);
            n.setSlaHours(s.getSlaHours() == null ? null : BigDecimal.valueOf(s.getSlaHours()));
            // 未显式指定时按节点类型推默认值：办理类节点（出纳/用印）默认必须上传凭证
            boolean needAttach = s.getRequireAttachment() != null
                    ? s.getRequireAttachment()
                    : (n.getNodeType() != null && n.getNodeType() == FlowNodeTemplate.TYPE_HANDLE);
            n.setRequireAttachment(needAttach ? 1 : 0);
            nodeMapper.insert(n);

            if (StringUtils.hasText(s.getRuleType())) {
                FlowNodeAssignee rule = new FlowNodeAssignee();
                rule.setNodeId(n.getId());
                rule.setRuleType(s.getRuleType());
                rule.setRuleValue(s.getRuleValue());
                rule.setSignMode(1);
                rule.setSortNo(0);
                assigneeMapper.insert(rule);
            } else if (n.getNodeType() != FlowNodeTemplate.TYPE_START) {
                // 没有规则的审批/办理节点是可部署的，但运行时解析不出处理人。
                // 这里把风险摆到日志和「流程预览」里，而不是让它悄无声息地上线。
                log.warn("节点「{}」未配置指派规则，该节点运行时将解析不出处理人，请用流程预览确认", s.getName());
            }
        }
    }

    /** 该单据类型下当前生效的版本全部下线 */
    private void retireActive(Long docTypeId) {
        configMapper.update(null, Wrappers.<FlowConfig>lambdaUpdate()
                .eq(FlowConfig::getDocTypeId, docTypeId)
                .eq(FlowConfig::getStatus, STATUS_ACTIVE)
                .set(FlowConfig::getStatus, STATUS_RETIRED)
                .set(FlowConfig::getUpdatedBy, UserContext.currentUserId()));
    }

    /** 下一个版本号：取该单据类型历史最大版本 + 1，避开 uk_flow_cfg(doc_type_id, version) */
    private int nextVersion(Long docTypeId) {
        List<FlowConfig> all = configMapper.selectList(Wrappers.<FlowConfig>lambdaQuery()
                .eq(FlowConfig::getDocTypeId, docTypeId));
        return all.stream()
                .map(FlowConfig::getVersion)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0) + 1;
    }

    private String buildProcDefKey(Long docTypeId, int version) {
        String code = configMapper.selectDocTypeCode(docTypeId);
        String base = StringUtils.hasText(code) ? code : ("FLOW_" + docTypeId);
        return base + "_V" + version;
    }

    /** 业务大类跟随单据类型，保证与单据列表的分类口径一致 */
    private String resolveCategory(String given, Long docTypeId) {
        String fromDocType = configMapper.selectDocTypeCategory(docTypeId);
        if (StringUtils.hasText(fromDocType)) {
            return fromDocType;
        }
        if (!StringUtils.hasText(given)) {
            return "DAILY";
        }
        return switch (given.trim()) {
            case "日常审批", "日常", "DAILY" -> "DAILY";
            case "业务单据", "业务", "BIZ" -> "BIZ";
            case "员工报销", "报销", "REIMBURSE" -> "REIMBURSE";
            case "用印", "用章", "SEAL" -> "SEAL";
            default -> given.trim();
        };
    }

    private Long requireDocType(Long docTypeId) {
        if (docTypeId == null) {
            throw BizException.of("请选择该流程关联的单据类型");
        }
        if (configMapper.countDocType(docTypeId) == 0) {
            throw BizException.of("单据类型不存在（id=%s）", docTypeId);
        }
        return docTypeId;
    }

    private FlowConfig requireConfig(Long id) {
        FlowConfig cfg = configMapper.selectById(id);
        if (cfg == null) {
            throw BizException.notFound("流程配置不存在: " + id);
        }
        return cfg;
    }

    /* ------------------------------------------------------------------ 入参 */

    @lombok.Data
    public static class NodeAssignReq {
        private Long nodeId;
        private String nodeKey;
        private String nodeName;
        private List<RuleItem> rules;
    }

    @lombok.Data
    public static class RuleItem {
        private String ruleType;
        private String ruleValue;
        private Integer signMode;
    }
}
