package com.hxj.oa.flow.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hxj.oa.common.exception.BizException;
import com.hxj.oa.common.security.UserContext;
import com.hxj.oa.common.util.JsonColumn;
import com.hxj.oa.common.util.JsonUtils;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
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
    /**
     * condition_expr 列的容量上限（VARCHAR(1024)）。
     * 在服务层先按上限拦一道，是为了把"塞不下"变成一个能看懂的业务提示，
     * 而不是 MySQL 抛出来的 DataIntegrityViolation（用户看到的就是一句 500）。
     */
    private static final int CONDITION_EXPR_MAX_CHARS = 1000;

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
            // 只改了名称/分类，节点沿用当前版本的：按"保留式"重建（含网关与分支），
            // 绝不按名字重新解析 —— 那会把网关解析成审批节点、把分支和定制规则抹掉
            req.setNodeItems(itemsFromExisting(id));
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
        Specs specs = toSpecs(req);
        assertHasExecutableNode(specs.specs());
        resolveGatewayBranches(specs);

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

        writeNodes(cfg.getId(), specs.specs());
        // 让后续新提交的单据走这条新链路
        configMapper.updateDocTypeFlowConfig(docTypeId, cfg.getId());

        FlowConfig deployed = flowDeployService.deploy(cfg.getId());
        log.info("流程配置已保存并部署 id={} name={} docTypeId={} version={} procDefKey={} deployStatus={}",
                deployed.getId(), deployed.getName(), docTypeId, version, procDefKey, deployed.getDeployStatus());
        return deployed;
    }

    /**
     * 结构化节点定义 + 「提交位置」的对应关系。
     *
     * <p>位置号（1 开始）是条件分支指向目标的唯一坐标，所以 items 与 specs
     * <b>必须严格一一对应</b>（下标 i 的 item 解析出下标 i 的 spec）——
     * 任何"过滤掉一个元素"的写法都会让分支指向隔壁节点，而且是静默的。
     * 因此这里把两者绑在一个不可变载体里返回，而不是各自返回一个 List。
     */
    private record Specs(List<FlowSaveReq.NodeItem> items, List<FlowNodeTemplate.NodeTemplate> specs) {
    }

    /** 节点规格：结构化 nodeItems 优先，否则按名称走模板库翻译 */
    private Specs toSpecs(FlowSaveReq req) {
        if (req.getNodeItems() != null && !req.getNodeItems().isEmpty()) {
            List<FlowSaveReq.NodeItem> items = new ArrayList<>();
            List<FlowNodeTemplate.NodeTemplate> specs = new ArrayList<>();
            for (FlowSaveReq.NodeItem item : req.getNodeItems()) {
                if (item == null || !StringUtils.hasText(item.getNodeName())) {
                    continue;
                }
                items.add(item);
                specs.add(toSpec(item));
            }
            return new Specs(items, specs);
        }
        if (req.getNodes() == null || req.getNodes().isEmpty()) {
            throw BizException.of("审批节点不能为空，至少需要一个审批或办理节点");
        }
        List<FlowSaveReq.NodeItem> items = new ArrayList<>();
        List<FlowNodeTemplate.NodeTemplate> specs = new ArrayList<>();
        for (String raw : req.getNodes()) {
            if (!StringUtils.hasText(raw)) {
                continue;
            }
            FlowSaveReq.NodeItem item = new FlowSaveReq.NodeItem();
            item.setNodeName(raw);
            items.add(item);
            specs.add(nodeTemplate.resolve(raw));
        }
        return new Specs(items, specs);
    }

    /**
     * 单个节点：显式 nodeType=3（条件分支）走网关工厂，其余走模板库翻译。
     *
     * <p>网关必须特判，不能"先按名字解析再改类型"——网关的名字（「金额分支」）
     * 在模板库里没有预设，会命中"未解析出处理人"的兜底分支、白刷一条 warn，
     * 还会把一个永远解析不出人的空规则带进规则表。
     */
    private FlowNodeTemplate.NodeTemplate toSpec(FlowSaveReq.NodeItem item) {
        boolean gateway = item.getNodeType() != null && item.getNodeType() == FlowNodeTemplate.TYPE_GATEWAY;
        FlowNodeTemplate.NodeTemplate t = gateway
                ? nodeTemplate.gateway(item.getNodeName())
                : nodeTemplate.resolve(item.getNodeName());
        if (item.getNodeType() != null && !gateway) {
            t.setNodeType(item.getNodeType());
        }
        if (!gateway && StringUtils.hasText(item.getRuleType())) {
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
        return t;
    }

    /* --------------------------------------------------------- 条件分支解析 */

    /**
     * 校验并解析全部条件分支，把客户端给的「位置号」翻译成最终 nodeKey（{@code n{seq}}）。
     *
     * <h3>规则与理由</h3>
     * <ol>
     *   <li><b>只有网关能配分支</b>：审批节点上的 conditionExpr 引擎根本不读，写了就是骗人。</li>
     *   <li><b>网关后面必须还有节点</b>：分支没有去处时 BPMN 生成出来是断的。</li>
     *   <li><b>只能指向网关之后的节点</b>：禁止回跳，等于从结构上排除了死循环——
     *       驳回退回到指定节点是另一个机制（allowReject），不该靠分支回跳实现。</li>
     *   <li><b>「否则」分支最多一条；一条都没有时自动补一条指向紧随节点</b>，
     *       与 {@code BpmnGenerator} 的兜底行为保持一致，保证 else 永远有去处。</li>
     *   <li><b>紧随其后的那个节点必须被某条分支指向</b>：这是最容易漏的一条——
     *       网关之后的第一个节点，入边完全依赖分支（BpmnGenerator 在网关后会把
     *       "上一条边"置空）。它若没被任何分支指向，生成的 BPMN 里这个 userTask
     *       就没有 incoming sequenceFlow，部署时直接报错。宁可在这里拦下来并说清楚。</li>
     * </ol>
     */
    private void resolveGatewayBranches(Specs s) {
        List<FlowSaveReq.NodeItem> items = s.items();
        List<FlowNodeTemplate.NodeTemplate> specs = s.specs();

        for (int i = 0; i < specs.size(); i++) {
            FlowNodeTemplate.NodeTemplate t = specs.get(i);
            List<FlowSaveReq.BranchItem> given = items.get(i).getBranches();
            boolean gateway = t.getNodeType() != null && t.getNodeType() == FlowNodeTemplate.TYPE_GATEWAY;

            if (!gateway) {
                if (given != null && !given.isEmpty()) {
                    throw BizException.of("节点「%s」不是条件分支节点，不能配置分支条件", t.getName());
                }
                continue;
            }
            t.setBranches(buildBranches(t.getName(), given, specs, i + 1));
        }
    }

    private List<FlowNodeTemplate.Branch> buildBranches(String name, List<FlowSaveReq.BranchItem> given,
                                                        List<FlowNodeTemplate.NodeTemplate> specs, int gatewaySeq) {
        int nextSeq = firstFlowSeqAfter(specs, gatewaySeq);
        if (nextSeq == 0) {
            throw BizException.of("条件分支「%s」后面必须至少还有一个审批或办理节点，否则分支没有去处", name);
        }
        if (given == null || given.isEmpty()) {
            throw BizException.of("条件分支「%s」至少需要一条分支条件", name);
        }

        List<FlowNodeTemplate.Branch> out = new ArrayList<>();
        boolean hasDefault = false;
        for (FlowSaveReq.BranchItem b : given) {
            if (b == null) {
                continue;
            }
            Integer target = b.getTarget();
            if (target == null) {
                throw BizException.of("条件分支「%s」有一条分支没有选择目标节点", name);
            }
            if (target < 1 || target > specs.size()) {
                throw BizException.of("条件分支「%s」的目标节点位置 %d 不存在（当前共 %d 个节点）",
                        name, target, specs.size());
            }
            FlowNodeTemplate.NodeTemplate targetNode = specs.get(target - 1);
            if (targetNode.getNodeType() != null && targetNode.getNodeType() == FlowNodeTemplate.TYPE_START) {
                throw BizException.of("条件分支「%s」的分支不能指向发起节点「%s」", name, targetNode.getName());
            }
            if (target <= gatewaySeq) {
                throw BizException.of("条件分支「%s」的分支只能指向它之后的节点，而「%s」在它之前（位置 %d）",
                        name, targetNode.getName(), target);
            }

            if (Boolean.TRUE.equals(b.getDefaultBranch())) {
                if (hasDefault) {
                    throw BizException.of("条件分支「%s」只允许一条「否则」分支", name);
                }
                hasDefault = true;
                out.add(branch(null, "n" + target, true));
            } else {
                String expr = b.getExpr() == null ? "" : b.getExpr().trim();
                if (expr.isEmpty()) {
                    throw BizException.of("条件分支「%s」有一条分支既没有条件表达式、也没有勾选「否则」", name);
                }
                try {
                    ConditionExprValidator.validate(expr);
                } catch (BizException e) {
                    // 把"哪条分支的哪个条件"补进消息里，否则用户不知道该改哪一行
                    throw BizException.of("条件分支「%s」的条件「%s」不合法：%s", name, expr, e.getMessage());
                }
                out.add(branch(expr, "n" + target, false));
            }
        }
        if (out.isEmpty()) {
            throw BizException.of("条件分支「%s」至少需要一条分支条件", name);
        }
        if (!hasDefault) {
            out.add(branch(null, "n" + nextSeq, true));
        }
        String nextKey = "n" + nextSeq;
        boolean coversNext = out.stream().anyMatch(x -> nextKey.equals(x.getTarget()));
        if (!coversNext) {
            throw BizException.of("条件分支「%s」后面紧跟的节点「%s」必须被某条分支指向，"
                            + "否则该节点没有入边、流程无法部署（可把「否则」分支指向它）",
                    name, specs.get(nextSeq - 1).getName());
        }
        return out;
    }

    /** 网关之后第一个非发起节点的位置号（1 开始）；后面没有节点则返回 0 */
    private int firstFlowSeqAfter(List<FlowNodeTemplate.NodeTemplate> specs, int gatewaySeq) {
        for (int i = gatewaySeq; i < specs.size(); i++) {
            FlowNodeTemplate.NodeTemplate t = specs.get(i);
            if (t.getNodeType() == null || t.getNodeType() != FlowNodeTemplate.TYPE_START) {
                return i + 1;
            }
        }
        return 0;
    }

    private FlowNodeTemplate.Branch branch(String expr, String target, boolean defaultBranch) {
        FlowNodeTemplate.Branch b = new FlowNodeTemplate.Branch();
        b.setExpr(expr);
        b.setTarget(target);
        b.setDefaultBranch(defaultBranch);
        return b;
    }

    /**
     * 序列化成 {@code BpmnGenerator} 认得的格式（键顺序固定，便于人工核对与 diff）：
     * {@code [{"expr":"doc.amount >= 20000","target":"n4"},{"default":true,"target":"n5"}]}
     */
    private String branchesToJson(List<FlowNodeTemplate.Branch> branches) {
        List<Map<String, Object>> arr = new ArrayList<>();
        for (FlowNodeTemplate.Branch b : branches) {
            Map<String, Object> m = new LinkedHashMap<>();
            if (b.isDefaultBranch()) {
                m.put("default", true);
            } else {
                m.put("expr", b.getExpr());
            }
            m.put("target", b.getTarget());
            arr.add(m);
        }
        String json = JsonUtils.toJson(arr);
        if (json.length() > CONDITION_EXPR_MAX_CHARS) {
            throw BizException.of("条件分支的条件过长（序列化后 %d 个字符，上限 %d），"
                    + "请精简条件或拆成两个条件分支节点", json.length(), CONDITION_EXPR_MAX_CHARS);
        }
        return json;
    }

    /** 把库里存的 condition_expr 还原成「位置号」形态的分支提交体 */
    private List<FlowSaveReq.BranchItem> branchesToItems(String json, Map<String, Integer> positionByKey) {
        List<FlowSaveReq.BranchItem> out = new ArrayList<>();
        for (Map<String, Object> b : JsonColumn.toList(json)) {
            boolean isDefault = Boolean.TRUE.equals(JsonColumn.toBool(b, "default"));
            String target = JsonColumn.str(b, "target");
            Integer position = target == null ? null : positionByKey.get(target);
            if (position == null) {
                // 存量数据被手工改过库、target 指向了不存在的节点。
                // 这里必须报错而不是丢分支 —— 悄悄丢一条分支，等于把 else 悄悄挪走。
                throw BizException.of("条件分支的存量目标节点「%s」在流程中不存在，请重新指定分支目标", target);
            }
            FlowSaveReq.BranchItem item = new FlowSaveReq.BranchItem();
            item.setExpr(JsonColumn.str(b, "expr"));
            item.setTarget(position);
            item.setDefaultBranch(isDefault);
            out.add(item);
        }
        return out;
    }

    /**
     * 从库里既有节点拼出结构化提交体（保留 nodeType / 指派规则 / 条件分支）。
     *
     * <p>用在「只改名称、没提交节点」的修改请求上。以前这条路径是按节点<b>名字</b>
     * 重新走模板库解析的，后果是：网关被解析成审批节点、条件分支与定制过的指派规则
     * 全部消失 —— 一次改名就静默毁掉流程结构。改成保留式重建后，
     * 任何"只改名字"的调用都不再动结构。
     */
    private List<FlowSaveReq.NodeItem> itemsFromExisting(Long flowConfigId) {
        List<FlowConfigNode> nodes = nodeMapper.selectList(Wrappers.<FlowConfigNode>lambdaQuery()
                .eq(FlowConfigNode::getFlowConfigId, flowConfigId)
                .orderByAsc(FlowConfigNode::getSeqNo));
        Map<String, Integer> positionByKey = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            positionByKey.put(nodes.get(i).getNodeKey(), i + 1);
        }

        List<FlowSaveReq.NodeItem> items = new ArrayList<>();
        for (FlowConfigNode n : nodes) {
            FlowSaveReq.NodeItem item = new FlowSaveReq.NodeItem();
            item.setNodeName(n.getNodeName());
            item.setNodeType(n.getNodeType());
            item.setSlaHours(n.getSlaHours() == null ? null : n.getSlaHours().doubleValue());
            item.setAllowCountersign(n.getAllowCountersign() != null && n.getAllowCountersign() == 1);
            item.setRequireAttachment(n.getRequireAttachment() != null && n.getRequireAttachment() == 1);
            if (n.getNodeType() != null && n.getNodeType() == FlowNodeTemplate.TYPE_GATEWAY) {
                item.setBranches(branchesToItems(n.getConditionExpr(), positionByKey));
            } else {
                FlowNodeAssignee rule = assigneeMapper.selectOne(Wrappers.<FlowNodeAssignee>lambdaQuery()
                        .eq(FlowNodeAssignee::getNodeId, n.getId())
                        .orderByAsc(FlowNodeAssignee::getSortNo)
                        .last("LIMIT 1"));
                if (rule != null) {
                    item.setRuleType(rule.getRuleType());
                    item.setRuleValue(rule.getRuleValue());
                }
            }
            items.add(item);
        }
        return items;
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
            // 条件网关：把已解析好的分支落库（BpmnGenerator 从这里读，生成 exclusiveGateway 的分支边）
            if (s.getBranches() != null && !s.getBranches().isEmpty()) {
                n.setConditionExpr(branchesToJson(s.getBranches()));
            }
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
