package com.hxj.oa.flow.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hxj.oa.common.exception.BizException;
import com.hxj.oa.common.security.LoginUser;
import com.hxj.oa.flow.dto.ApprovalRequest;
import com.hxj.oa.flow.dto.FlowStartRequest;
import com.hxj.oa.flow.entity.FlowConfig;
import com.hxj.oa.flow.entity.FlowConfigNode;
import com.hxj.oa.flow.entity.FlowInstance;
import com.hxj.oa.flow.entity.FlowInstanceNode;
import com.hxj.oa.flow.event.FlowLifecycleEvent;
import com.hxj.oa.flow.mapper.FlowConfigMapper;
import com.hxj.oa.flow.mapper.FlowConfigNodeMapper;
import com.hxj.oa.flow.mapper.FlowInstanceMapper;
import com.hxj.oa.flow.mapper.FlowInstanceNodeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.task.api.Task;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 流程运行时：启动、审批、驳回、自动跳过。
 *
 * 状态机约定（闭合设计，避免原型里「approve 只弹消息不改状态」的问题）：
 * <pre>
 *   单据 submitted ──start──▶ 流程运行中 ──全部节点通过──▶ 流程结束 / 单据已通过
 *                                   │
 *                                   ├── 驳回(默认) ──▶ 终止实例 / 单据已驳回 / 可修改后重新提交
 *                                   └── 驳回(指定节点) ──▶ changeState 跳回该节点继续
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlowRuntimeService {

    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final FlowConfigMapper configMapper;
    private final FlowConfigNodeMapper configNodeMapper;
    private final FlowInstanceMapper instanceMapper;
    private final FlowInstanceNodeMapper instanceNodeMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final DelegationService delegationService;

    // ============================================================ 启动

    /**
     * 启动流程实例。
     * 顺序很关键：先落业务实例（拿到 flowInstanceId）→ 再启动引擎，
     * 这样 TaskListener 触发时能通过流程变量找到业务实例，完成待办投影。
     */
    @Transactional(rollbackFor = Exception.class)
    public FlowInstance start(FlowConfig config, FlowStartRequest req) {
        if (config == null || config.getProcDefKey() == null) {
            throw new BizException("流程未部署，无法发起：" + (config == null ? "-" : config.getName()));
        }

        FlowInstance inst = new FlowInstance();
        inst.setDocumentId(req.getDocumentId());
        inst.setFlowConfigId(config.getId());
        inst.setFlowConfigVersion(config.getVersion());
        inst.setStatus(1);
        inst.setBusinessKey(req.getDocNo());
        inst.setBizCategory(req.getBizCategory());
        inst.setStartedAt(LocalDateTime.now());
        instanceMapper.insert(inst);

        Map<String, Object> vars = new HashMap<>();
        vars.put("flowInstanceId", inst.getId());
        vars.put("flowConfigId", config.getId());
        vars.put("documentId", req.getDocumentId());
        vars.put("docNo", req.getDocNo());
        vars.put("companyId", req.getCompanyId());
        vars.put("applicantId", req.getApplicantId());
        vars.put("applicantDeptId", req.getApplicantDeptId());
        vars.put("bizCategory", req.getBizCategory());
        vars.put("docTypeId", req.getDocTypeId());
        vars.put("amount", req.getAmount() == null ? BigDecimal.ZERO : req.getAmount());

        // 表单字段平铺为顶层流程变量：条件网关与 condition 类规则直接引用
        StringBuilder formKeys = new StringBuilder();
        if (req.getFormData() != null) {
            req.getFormData().forEach((k, v) -> {
                if (v == null || k == null || k.contains(".")) {
                    return;
                }
                if (v instanceof Map || v instanceof List) {
                    return; // 复杂结构不进流程变量，避免序列化差异
                }
                vars.put(k, v);
                if (!formKeys.isEmpty()) {
                    formKeys.append(',');
                }
                formKeys.append(k);
            });
        }
        vars.put("formVarKeys", formKeys.toString());

        ProcessInstance pi = runtimeService.startProcessInstanceByKey(
                config.getProcDefKey(), req.getDocNo(), vars);

        inst.setProcInstId(pi.getId());
        syncCurrentNode(inst);
        instanceMapper.updateById(inst);

        boolean finishedOnStart = autoSkipUnassigned(pi.getId(), inst);

        // ⚠ 只有流程**没有**在启动阶段就办结时才发 STARTED。
        // 原实现无条件发 STARTED：autoSkip 跳完最后一个节点时已经 publish(FINISHED)
        // （单据 status=3），紧接着这里又把它覆盖回 status=2 ⇒ **幽灵单**：
        // 单据显示"审批中"，但 flow_instance.ended_at 有值、ACT_RU_TASK=0，
        // 待办和已办都查不到它。单管理员冷启动（唯一节点=自己的部门负责人）100% 必踩。
        if (finishedOnStart) {
            log.info("流程启动后即办结（无待处理节点），不再发 STARTED 以免覆盖办结状态 docNo={}",
                    req.getDocNo());
        } else {
            eventPublisher.publishEvent(new FlowLifecycleEvent(this, FlowLifecycleEvent.Stage.STARTED,
                    req.getDocumentId(), inst.getId(), inst.getCurrentNodeKey(),
                    configNodeName(inst, inst.getCurrentNodeKey()), "发起申请",
                    req.getApplicantId(), null));
        }

        log.info("流程启动 docNo={} procDefKey={} procInstId={} 当前节点={}",
                req.getDocNo(), config.getProcDefKey(), pi.getId(), inst.getCurrentNodeKey());
        return instanceMapper.selectById(inst.getId());
    }

    // ============================================================ 审批

    @Transactional(rollbackFor = Exception.class)
    public FlowInstanceNode approve(ApprovalRequest req, LoginUser user) {
        Task task = requireTask(req.getTaskId());
        assertAssignee(task, user);

        FlowInstanceNode nodeRec = currentNodeRecord(task.getId());
        FlowInstance inst = requireInstance(task.getProcessInstanceId());
        LocalDateTime now = LocalDateTime.now();

        // 白名单：只有明确的 action 才放行，未知值一律报错。
        //
        // 【为什么不能"除 reject 外都算通过"】审批是不可逆动作。原先只判 "reject"，
        // 于是任何非空值（拼错成 "agree"/"aprove"、传 "withdraw"、"submit"，
        // 或将来新增动作名而调用方先上了线）都会**静默走通过分支** ——
        // 一个 typo 就等于替审批人签了字，且不留任何痕迹。
        // 与《管理员角色与权限设计说明》P5「失败必须显式」相反，这里 fail-fast。
        String action = req.getAction() == null ? "" : req.getAction().trim();
        if ("reject".equalsIgnoreCase(action)) {
            return doReject(task, nodeRec, inst, req, user, now);
        }
        if (!"approve".equalsIgnoreCase(action)) {
            throw new BizException(400, "不支持的审批动作：" + req.getAction()
                    + "（仅支持 approve=通过 / reject=驳回）");
        }

        // ---- 通过 ----
        if (nodeRec != null) {
            nodeRec.setStatus(2);
            nodeRec.setAction("approve");
            nodeRec.setCommentText(req.getComment());
            nodeRec.setAssigneeId(user.getUserId());
            nodeRec.setAssigneeName(user.getRealName());
            nodeRec.setActionAt(now);
            instanceNodeMapper.updateById(nodeRec);
        }
        if (req.getComment() != null && !req.getComment().isBlank()) {
            taskService.addComment(task.getId(), task.getProcessInstanceId(), req.getComment());
        }
        taskService.complete(task.getId());
        log.info("节点通过 docNo={} nodeKey={} by={}", inst.getBusinessKey(), task.getTaskDefinitionKey(), user.getRealName());

        // ---- 推进后：判断流程是否结束 ----
        ProcessInstance pi = runtimeService.createProcessInstanceQuery()
                .processInstanceId(task.getProcessInstanceId()).singleResult();
        if (pi == null) {
            inst.setStatus(2);
            inst.setEndedAt(now);
            inst.setCurrentNodeKey(null);
            instanceMapper.updateById(inst);
            // 先发 APPROVED（清空当前节点）再发 FINISHED，顺序不能反：
            // APPROVED 会把状态置为「审批中」，若在其后发会把已通过覆盖掉
            publish(FlowLifecycleEvent.Stage.APPROVED, inst, null, null, user, req.getComment());
            publish(FlowLifecycleEvent.Stage.FINISHED, inst, null, null, user, "流程结束");
            log.info("流程结束 docNo={}", inst.getBusinessKey());
        } else {
            syncCurrentNode(inst);
            instanceMapper.updateById(inst);
            // 事件必须带推进后的节点，单据的「当前节点」才能跟着走
            String nextKey = inst.getCurrentNodeKey();
            publish(FlowLifecycleEvent.Stage.APPROVED, inst, nextKey, configNodeName(inst, nextKey),
                    user, req.getComment());
            autoSkipUnassigned(pi.getId(), inst);
        }
        return nodeRec;
    }

    /** 驳回：默认终止实例退回发起人；指定节点则用 changeState 跳回 */
    private FlowInstanceNode doReject(Task task, FlowInstanceNode nodeRec, FlowInstance inst,
                                      ApprovalRequest req, LoginUser user, LocalDateTime now) {
        String rejectTo = req.getRejectTo();
        boolean backToSpecificNode = rejectTo != null && !rejectTo.isBlank()
                && !"START".equalsIgnoreCase(rejectTo) && !"PREV".equalsIgnoreCase(rejectTo);

        if (nodeRec != null) {
            nodeRec.setStatus(3);
            nodeRec.setAction("reject");
            nodeRec.setCommentText(req.getComment());
            nodeRec.setRejectLevel(rejectTo);
            nodeRec.setMaterials(req.getMaterials());
            nodeRec.setAssigneeId(user.getUserId());
            nodeRec.setAssigneeName(user.getRealName());
            nodeRec.setActionAt(now);
            instanceNodeMapper.updateById(nodeRec);
        }

        if (backToSpecificNode) {
            try {
                runtimeService.createChangeActivityStateBuilder()
                        .processInstanceId(task.getProcessInstanceId())
                        .moveActivityIdTo(task.getTaskDefinitionKey(), rejectTo)
                        .changeState();
                syncCurrentNode(inst);
                instanceMapper.updateById(inst);
                log.info("节点驳回并跳转 docNo={} from={} to={} by={}",
                        inst.getBusinessKey(), task.getTaskDefinitionKey(), rejectTo, user.getRealName());
            } catch (Exception e) {
                log.error("驳回跳转失败，降级为终止实例 docNo={} to={}", inst.getBusinessKey(), rejectTo, e);
                terminate(inst, task.getProcessInstanceId(), "驳回并终止：" + safe(req.getComment()));
            }
        } else {
            terminate(inst, task.getProcessInstanceId(), "驳回： " + safe(req.getComment()));
        }

        publish(FlowLifecycleEvent.Stage.REJECTED, inst, task.getTaskDefinitionKey(), null, user, req.getComment());
        return nodeRec;
    }

    private void terminate(FlowInstance inst, String procInstId, String reason) {
        runtimeService.deleteProcessInstance(procInstId, reason);
        inst.setStatus(3);
        inst.setEndedAt(LocalDateTime.now());
        instanceMapper.updateById(inst);
    }

    /** 发起人主动撤回：终止引擎实例，单据回到可修改状态 */
    @Transactional(rollbackFor = Exception.class)
    public void withdraw(FlowInstance inst, String reason) {        if (inst == null) {
            return;
        }
        if (inst.getProcInstId() != null) {
            ProcessInstance pi = runtimeService.createProcessInstanceQuery()
                    .processInstanceId(inst.getProcInstId()).singleResult();
            if (pi != null) {
                runtimeService.deleteProcessInstance(inst.getProcInstId(), reason);
            }
        }
        inst.setStatus(3);
        inst.setEndedAt(LocalDateTime.now());
        instanceMapper.updateById(inst);
        log.info("流程撤回 docNo={} reason={}", inst.getBusinessKey(), reason);
    }

    /**
     * 加签：把目标用户加入当前任务的候选池。
     * P0 采用「同层或签」，即原处理人与加签人同在候选池；
     * 若需要「前加签/后加签」等更细语义，在 flow_config_node 上扩展加签策略字段即可。
     */
    @Transactional(rollbackFor = Exception.class)
    public void countersign(String taskId, Long targetUserId, LoginUser operator) {
        Task task = requireTask(taskId);
        assertAssignee(task, operator);
        taskService.addCandidateUser(taskId, String.valueOf(targetUserId));

        FlowInstanceNode rec = currentNodeRecord(taskId);
        if (rec != null) {
            rec.setAction("countersign");
            String prev = rec.getCommentText() == null ? "" : rec.getCommentText() + " | ";
            rec.setCommentText(prev + "加签用户 " + targetUserId);
            instanceNodeMapper.updateById(rec);
        }
        log.info("加签 taskId={} targetUserId={} by={}", taskId, targetUserId, operator.getRealName());
    }

    // ============================================================ 无处理人自动跳过

    /**
     * 若某节点解析不出任何处理人（例如申请人就是自己的部门负责人），
     * 该节点不应把流程卡死 —— 记录一条自动跳过记录后直接完成。
     *
     * @return 流程是否**已经办结**。调用方必须据此决定要不要再发中间态事件：
     *         {@code start()} 如果无条件发 STARTED，会把刚刚 publish(FINISHED) 的
     *         单据状态从"已通过"覆盖回"审批中"，产生**幽灵单**（待办/已办都查不到）。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean autoSkipUnassigned(String procInstId, FlowInstance inst) {
        for (int guard = 0; guard < 10; guard++) {
            List<Task> tasks = taskService.createTaskQuery().processInstanceId(procInstId).list();
            if (tasks.isEmpty()) {
                // 没有任何待处理节点。可能引擎实例已经结束（例如流程定义里没有用户任务、
                // 或全部节点都在别处被跳过）—— 那种情况下单据会被留在"待审/审批中"却
                // 没有任何入口，是幽灵单的另一种形态，这里补一次 FINISHED 把它闭上。
                boolean ended = processEnded(procInstId);
                if (ended && inst.getEndedAt() == null) {
                    inst.setStatus(2);
                    inst.setEndedAt(LocalDateTime.now());
                    instanceMapper.updateById(inst);
                    publish(FlowLifecycleEvent.Stage.FINISHED, inst, null, null, null, "流程结束");
                    log.info("无可处理节点且引擎实例已结束，补发办结事件 docNo={}", inst.getBusinessKey());
                }
                return ended;
            }
            Task pending = tasks.stream().filter(this::hasNoAssignee).findFirst().orElse(null);
            if (pending == null) {
                // 每个任务都有办理人 ⇒ 流程正常停在某节点，尚未办结
                return processEnded(procInstId);
            }

            FlowInstanceNode rec = currentNodeRecord(pending.getId());
            if (rec != null) {
                rec.setStatus(4);
                rec.setAction("auto_skip");
                rec.setCommentText("无匹配审批人，系统自动通过");
                rec.setActionAt(LocalDateTime.now());
                instanceNodeMapper.updateById(rec);
            }
            log.warn("节点无处理人，自动跳过 docNo={} nodeKey={}", inst.getBusinessKey(), pending.getTaskDefinitionKey());
            taskService.complete(pending.getId());

            // 跳过后要按「接下来所处节点」回写，否则单据当前节点会停在被跳过的那个节点上
            syncCurrentNode(inst);
            instanceMapper.updateById(inst);
            String nextKey = inst.getCurrentNodeKey();
            publish(FlowLifecycleEvent.Stage.AUTO_SKIPPED, inst, nextKey, configNodeName(inst, nextKey), null,
                    "无匹配审批人，自动通过");

            // 被跳过的正好是最后一个节点时，引擎实例已结束，需要补一次办结事件
            ProcessInstance still = runtimeService.createProcessInstanceQuery()
                    .processInstanceId(procInstId).singleResult();
            if (still == null) {
                inst.setStatus(2);
                inst.setEndedAt(LocalDateTime.now());
                instanceMapper.updateById(inst);
                publish(FlowLifecycleEvent.Stage.FINISHED, inst, null, null, null, "流程结束");
                log.info("所有剩余节点自动跳过后流程结束 docNo={}", inst.getBusinessKey());
                return true;
            }
        }
        log.error("自动跳过次数超过保护阈值，可能存在流程配置环路 procInstId={}", procInstId);
        return processEnded(procInstId);
    }

    /** 引擎实例是否已结束。判断"流程是否办结"以引擎为准，不以我们自己有没有发过事件为准。 */
    private boolean processEnded(String procInstId) {
        return runtimeService.createProcessInstanceQuery()
                .processInstanceId(procInstId).singleResult() == null;
    }

    private boolean hasNoAssignee(Task task) {
        if (task.getAssignee() != null && !task.getAssignee().isBlank()) {
            return false;
        }
        List<IdentityLink> links = taskService.getIdentityLinksForTask(task.getId());
        return links == null || links.isEmpty();
    }

    // ============================================================ 辅助

    /** 把业务实例的 current_node_key 同步为引擎当前活跃任务 */
    private void syncCurrentNode(FlowInstance inst) {
        if (inst.getProcInstId() == null) {
            return;
        }
        List<Task> tasks = taskService.createTaskQuery()
                .processInstanceId(inst.getProcInstId())
                .orderByTaskCreateTime().asc()
                .list();
        if (tasks.isEmpty()) {
            inst.setCurrentNodeKey(null);
            return;
        }
        Task t = tasks.get(0);
        inst.setCurrentNodeKey(t.getTaskDefinitionKey());
    }

    public List<FlowInstanceNode> history(Long documentId) {
        return instanceNodeMapper.selectHistoryByDocument(documentId);
    }

    public FlowInstance byDocument(Long documentId) {
        return instanceMapper.selectOne(Wrappers.<FlowInstance>lambdaQuery()
                .eq(FlowInstance::getDocumentId, documentId)
                .orderByDesc(FlowInstance::getId)
                .last("LIMIT 1"));
    }

    private Task requireTask(String taskId) {
        Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
        if (task == null) {
            throw BizException.notFound("任务不存在或已被处理");
        }
        return task;
    }

    private FlowInstance requireInstance(String procInstId) {
        FlowInstance inst = instanceMapper.selectOne(Wrappers.<FlowInstance>lambdaQuery()
                .eq(FlowInstance::getProcInstId, procInstId)
                .last("LIMIT 1"));
        if (inst == null) {
            throw BizException.notFound("流程实例不存在: " + procInstId);
        }
        return inst;
    }

    /**
     * 当前用户必须是该任务的办理人 / 候选人 / 或被委托的代办人。
     *
     * <p>委托放行的判定与"待办是否可见"共用同一份数据（{@link DelegationService}），
     * 两条路径必须同源：一边看得见、另一边批不了（或反之）是最难排查的一类问题。
     *
     * <p>类别用的是**流程实例上的快照**而不是调用方传参 —— 传参一旦漏传就会把
     * "只代某类单据"静默放宽成"代全部"，而这是权限范畴的事，不能有静默放宽的余地。
     *
     * <p>本方法在调用侧只有 {@link #approve} 与 {@link #countersign} 两处，
     * 都用各自的调用参数做了鉴权，本方法负责"这两处鉴权口径一致"。
     *
     * <p>【为什么这里没有 ADMIN 旁路】历史实现写成
     * {@code uid.equals(task.getAssignee()) || user.hasRole("ADMIN")}，
     * 效果是<b>持有 ADMIN 角色的账号可以代任意人批准任意节点的任务</b> ——
     * 这是全项目唯一一处真实的越权：它绕过的正是"谁是审批人"这个判定的全部机制
     * （节点指派规则、候选人池、委托校验）。而"审批决定权"按设计
     * （管理员角色与权限设计说明 §P3）<b>永不授予管理员</b>：
     * 管理员能改流程配置、能改角色，但不能替人签字。
     * 需要"看得见全部单据"时走 {@link com.hxj.oa.common.util.DataScopeHelper}
     * 的行级数据范围（role_data_scope），那是读；这里是写，两者不能混。
     */
    private void assertAssignee(Task task, LoginUser user) {
        String uid = String.valueOf(user.getUserId());
        if (uid.equals(task.getAssignee())) {
            return;
        }
        List<IdentityLink> links = taskService.getIdentityLinksForTask(task.getId());
        boolean candidate = links != null && links.stream().anyMatch(l -> uid.equals(l.getUserId()));
        if (candidate) {
            return;
        }
        // 代办的判定：本任务的办理人是否把审批委托给了我（且在有效期内、类别匹配）
        FlowInstance inst = requireInstance(task.getProcessInstanceId());
        Long assigneeId = null;
        try {
            assigneeId = task.getAssignee() == null ? null : Long.valueOf(task.getAssignee());
        } catch (NumberFormatException ignored) {
            // 候选人模式（assignee 为空）下不会有委托关系，直接视为无委托
        }
        if (delegationService.canActFor(assigneeId, user.getUserId(), inst.getBizCategory())) {
            log.info("委托放行 docNo={} nodeKey={} 代办人={} 原承办人={}",
                    inst.getBusinessKey(), task.getTaskDefinitionKey(), user.getRealName(), assigneeId);
            return;
        }
        throw BizException.forbidden("您不是该节点的处理人，无权操作");
    }

    private FlowInstanceNode currentNodeRecord(String taskId) {
        return instanceNodeMapper.selectOne(Wrappers.<FlowInstanceNode>lambdaQuery()
                .eq(FlowInstanceNode::getTaskId, taskId)
                .orderByDesc(FlowInstanceNode::getId)
                .last("LIMIT 1"));
    }

    /**
     * 发布流程生命周期事件。
     *
     * nodeKey / nodeName 语义约定为「事件发生后，单据所处（即将停留）的节点」：
     * 对于 APPROVED / AUTO_SKIPPED，必须传推进后的下一个节点，
     * 否则单据上的当前节点会一直停在刚刚办完的那一步。
     */
    private void publish(FlowLifecycleEvent.Stage stage, FlowInstance inst, String nodeKey,
                         String nodeName, LoginUser user, String comment) {
        eventPublisher.publishEvent(new FlowLifecycleEvent(this, stage, inst.getDocumentId(), inst.getId(),
                nodeKey, nodeName, comment,
                user == null ? null : user.getUserId(), user == null ? null : user.getRealName()));
    }

    /** 取节点配置里的显示名，用于把「当前节点」落成人类可读文案 */
    private String configNodeName(FlowInstance inst, String nodeKey) {
        if (nodeKey == null || inst.getFlowConfigId() == null) {
            return null;
        }
        FlowConfigNode node = configNodeMapper.selectOne(Wrappers.<FlowConfigNode>lambdaQuery()
                .eq(FlowConfigNode::getFlowConfigId, inst.getFlowConfigId())
                .eq(FlowConfigNode::getNodeKey, nodeKey)
                .last("LIMIT 1"));
        return node == null ? null : node.getNodeName();
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    /** 流程配置节点（供上层展示节点清单） */
    public List<FlowConfigNode> nodesOf(Long flowConfigId) {
        return configNodeMapper.selectList(Wrappers.<FlowConfigNode>lambdaQuery()
                .eq(FlowConfigNode::getFlowConfigId, flowConfigId)
                .orderByAsc(FlowConfigNode::getSeqNo));
    }

    public FlowConfig configOf(Long flowConfigId) {
        return configMapper.selectById(flowConfigId);
    }
}
