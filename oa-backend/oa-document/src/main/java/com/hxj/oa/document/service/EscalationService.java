package com.hxj.oa.document.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hxj.oa.document.dto.EscalationResultVO;
import com.hxj.oa.document.entity.Document;
import com.hxj.oa.document.entity.FlowEscalation;
import com.hxj.oa.document.entity.Notification;
import com.hxj.oa.document.mapper.DocumentMapper;
import com.hxj.oa.document.mapper.FlowEscalationMapper;
import com.hxj.oa.document.mapper.NotificationMapper;
import com.hxj.oa.flow.entity.FlowInstanceNode;
import com.hxj.oa.flow.mapper.FlowInstanceNodeMapper;
import org.flowable.engine.TaskService;
import com.hxj.oa.system.entity.Department;
import com.hxj.oa.system.entity.SysUser;
import com.hxj.oa.system.mapper.DepartmentMapper;
import com.hxj.oa.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 超时升级：节点超过处理时限后，把**上级部门负责人加签进来**一起推。
 *
 * <h3>需求状态（重要）</h3>
 * 用户明确表示"**目前还不知道有无这个需求**"，所以：
 * <ul>
 *   <li>自动升级**默认关闭**（{@code oa.flow.escalation.enabled=false}）——
 *       没这个需求时，它不会真的去打扰任何人；</li>
 *   <li>手动触发接口（{@code POST /api/flows/escalation/run}）**不受开关限制** ——
 *       它是运维的显式动作，也是用例做确定性验证的入口（否则只能等定时器，测不了）。</li>
 * </ul>
 *
 * <h3>为什么是"加签"而不是"改派"</h3>
 * 与审批委托同一套考虑：**不改写节点的 assignee**。
 * 改派会让流程历史里"谁该审"含义变味，也会让分支条件（依赖 assignee）产生歧义；
 * 而 {@code addCandidateUser} 让上级获得办理资格、原承办人保留，
 * 且复用了既有的"候选人可批"授权路径（见 {@code FlowRuntimeService#assertAssignee}）。
 *
 * <h3>幂等</h3>
 * 靠 {@code uk_escalation_node(node_id, deleted)} 唯一键：
 * 一个节点最多一条升级记录，重复执行撞键即视为"已升级过"。
 * 这比"先查再插"可靠 —— 定时任务与手动触发可能并发。
 *
 * <h3>升级给谁</h3>
 * 从单据所属部门出发，沿 {@code parent_id} 往上找到**第一个有负责人的祖先部门**
 * （不要求是直接上级：直接上级没配负责人时，再往上找比"放弃"更符合预期）。
 * 找不到就记一条 {@code status=0} 的台账并说明原因 —— 不静默跳过，
 * 否则"为什么没升级"永远说不清。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EscalationService {

    public static final int ST_ESCALATED = 1;
    public static final int ST_NO_LEADER = 0;

    /** 参与待办的节点类型（与 FlowInstanceNodeMapper#selectTodoByAssignee 同口径） */
    private static final List<Integer> TODO_NODE_TYPES = List.of(1, 4);
    private static final List<Integer> TODO_STATUS = List.of(0, 1);
    /** 往上找上级时最多爬几层，防止脏数据（环）导致死循环 */
    private static final int MAX_DEPT_DEPTH = 10;

    private final FlowInstanceNodeMapper instanceNodeMapper;
    private final FlowEscalationMapper escalationMapper;
    private final DocumentMapper documentMapper;
    private final DepartmentMapper deptMapper;
    private final SysUserMapper userMapper;
    private final NotificationMapper notificationMapper;
    private final TaskService taskService;

    @Value("${oa.flow.escalation.enabled:false}")
    private boolean autoEnabled;

    /**
     * 定时执行。默认**不打开开关就直接返回** —— 需求未确认前不动任何人。
     *
     * <p>间隔与首次延迟都可配；异常在这里兜住：定时任务抛异常会让后续调度静默停掉，
     * 那比"这次没升成"严重得多。
     */
    @Scheduled(initialDelayString = "${oa.flow.escalation.initial-delay-ms:60000}",
            fixedDelayString = "${oa.flow.escalation.interval-ms:600000}")
    public void scheduledRun() {
        if (!autoEnabled) {
            return;
        }
        try {
            EscalationResultVO r = escalateOverdue();
            if (r.getScanned() > 0) {
                log.info("超时升级（定时）：扫描 {} 条，升级 {} 条，未找到上级 {} 条，已升级过 {} 条，任务失效 {} 条",
                        r.getScanned(), r.getEscalated(), r.getNoLeader(), r.getAlreadyEscalated(), r.getStaleTask());
            }
        } catch (Exception e) {
            log.warn("超时升级（定时）执行失败，已忽略本次以免调度被终止：{}", e.toString(), e);
        }
    }

    /** 全局扫描（定时任务用） */
    public EscalationResultVO escalateOverdue() {
        return escalateOverdue(null);
    }

    /**
     * 扫描并升级超时节点。定时与手动触发共用这一份实现，口径不会漂。
     *
     * @param documentId 限定只处理某张单据的节点；null = 全部。
     *                   <p>为什么要有这个参数：全局扫描会**动到演示/生产库里所有超时节点**
     *                   —— 运维多半只想处理某张卡住的单，而验证用例更需要"只碰自己的夹具"。
     *                   第一版没有这个参数，用例一跑就给库里 9 个真实超时节点写上了升级台账，
     *                   才发现缺了它。
     */
    public EscalationResultVO escalateOverdue(Long documentId) {
        EscalationResultVO result = new EscalationResultVO();
        result.setAutoEnabled(autoEnabled);

        LocalDateTime now = LocalDateTime.now();
        List<FlowInstanceNode> overdue = instanceNodeMapper.selectList(Wrappers.<FlowInstanceNode>lambdaQuery()
                .eq(documentId != null, FlowInstanceNode::getDocumentId, documentId)
                .in(FlowInstanceNode::getNodeType, TODO_NODE_TYPES)
                .in(FlowInstanceNode::getStatus, TODO_STATUS)
                .isNotNull(FlowInstanceNode::getDeadline)
                .lt(FlowInstanceNode::getDeadline, now)
                .orderByAsc(FlowInstanceNode::getDeadline));
        result.setScanned(overdue.size());

        for (FlowInstanceNode node : overdue) {
            try {
                escalateOne(node, result);
            } catch (Exception e) {
                // 单条失败不能中断整批（与批量审批同一考虑）
                log.warn("超时升级单条失败 nodeId={} 原因={}", node.getId(), e.toString());
                result.getDetails().add("节点#" + node.getId() + " 升级失败：" + e.getMessage());
            }
        }
        return result;
    }

    private void escalateOne(FlowInstanceNode node, EscalationResultVO result) {
        if (escalationMapper.selectCount(Wrappers.<FlowEscalation>lambdaQuery()
                .eq(FlowEscalation::getNodeId, node.getId())) > 0) {
            result.setAlreadyEscalated(result.getAlreadyEscalated() + 1);
            return;
        }

        Document doc = documentMapper.selectById(node.getDocumentId());
        Department dept = doc == null ? null : deptMapper.selectById(doc.getDeptId());
        Long leaderId = findLeaderAbove(dept, node.getAssigneeId());
        SysUser leader = leaderId == null ? null : userMapper.selectById(leaderId);

        // 任务是否还存在（节点状态与引擎不一致时升级没有意义）
        boolean taskAlive = node.getTaskId() != null
                && taskService.createTaskQuery().taskId(node.getTaskId()).singleResult() != null;

        FlowEscalation rec = new FlowEscalation();
        rec.setCompanyId(doc == null ? null : doc.getCompanyId());
        rec.setDocumentId(node.getDocumentId());
        rec.setInstanceId(node.getInstanceId());
        rec.setNodeId(node.getId());
        rec.setNodeKey(node.getNodeKey());
        rec.setNodeName(node.getNodeName());
        rec.setTaskId(node.getTaskId());
        rec.setFromAssigneeId(node.getAssigneeId());

        if (!taskAlive) {
            rec.setStatus(ST_NO_LEADER);
            rec.setReason("节点的引擎任务已不存在（状态与引擎不一致），不升级");
            if (insertIdempotent(rec, result)) {
                result.setStaleTask(result.getStaleTask() + 1);
            }
            result.getDetails().add("节点#" + node.getId() + " 任务已失效，跳过");
            return;
        }
        if (leader == null) {
            rec.setStatus(ST_NO_LEADER);
            rec.setReason("沿部门树未找到有负责人的上级部门（单据部门="
                    + (dept == null ? "-" : dept.getName()) + "）");
            if (insertIdempotent(rec, result)) {
                result.setNoLeader(result.getNoLeader() + 1);
            }
            result.getDetails().add("节点#" + node.getId() + " 未找到上级负责人");
            log.warn("超时升级：{} 原因={}", rec.getReason(), node.getId());
            return;
        }

        // 加签：让上级获得办理资格（不改写 assignee）
        taskService.addCandidateUser(node.getTaskId(), String.valueOf(leaderId));
        rec.setStatus(ST_ESCALATED);
        rec.setToAssigneeId(leaderId);
        rec.setReason("超时升级给上级：" + leader.getRealName());
        if (!insertIdempotent(rec, result)) {
            return;   // 并发下已被处理过：不再重复加签与通知
        }
        result.setEscalated(result.getEscalated() + 1);
        result.getDetails().add("节点#" + node.getId() + " 已升级给 " + leader.getRealName());

        notify(doc, node, leader, leaderId);
        notify(doc, node, null, node.getAssigneeId());
        log.info("超时升级 docNo={} 节点={} 原承办人={} 升级给={}",
                doc == null ? "-" : doc.getDocNo(), node.getNodeName(), node.getAssigneeId(), leader.getRealName());
    }

    /** 插入升级台账；撞唯一键说明并发下已被另一次执行升级过，按"已升级过"计入。 */
    private boolean insertIdempotent(FlowEscalation rec, EscalationResultVO result) {
        try {
            escalationMapper.insert(rec);
            return true;
        } catch (DuplicateKeyException e) {
            // 并发下已被另一次执行处理过：计入"已升级过"，**并且不要再计入 escalated/noLeader**
            result.getDetails().add("节点#" + rec.getNodeId() + " 已被并发执行处理过，跳过");
            result.setAlreadyEscalated(result.getAlreadyEscalated() + 1);
            return false;
        }
    }

    private void notify(Document doc, FlowInstanceNode node, SysUser leader, Long receiverId) {
        if (receiverId == null || doc == null) {
            return;
        }
        Notification n = new Notification();
        n.setCompanyId(doc.getCompanyId());
        n.setReceiverId(receiverId);
        n.setNotifyType("todo");
        n.setBizType("document");
        n.setBizId(doc.getId());
        n.setChannel("inner");
        n.setIsRead(0);
        if (leader != null) {
            n.setTitle("有一条超时待办已升级给你");
            n.setContent("单据 " + doc.getDocNo() + " 在「" + node.getNodeName()
                    + "」已超过处理时限，已加签给你，请协助处理。");
        } else {
            n.setTitle("您的待办已超时并被升级");
            n.setContent("单据 " + doc.getDocNo() + " 在「" + node.getNodeName()
                    + "」已超过处理时限，已升级给您的上级，请尽快处理。");
        }
        notificationMapper.insert(n);
    }

    /**
     * 沿部门树往上找第一个有负责人的部门。
     *
     * <p>不要求"直接上级"：直接上级没配负责人时，继续往上找比直接放弃更符合预期
     * （否则配了 3 层的组织里，中间层没配负责人就会导致整条链升不上去）。
     * 排除承办人本人：他就是当前节点的人，升给他等于没升。
     */
    private Long findLeaderAbove(Department start, Long excludeUserId) {
        Department cur = start;
        int depth = 0;
        while (cur != null && depth++ < MAX_DEPT_DEPTH) {
            Long parentId = cur.getParentId();
            if (parentId == null || parentId == 0L) {
                return null;
            }
            Department parent = deptMapper.selectById(parentId);
            if (parent == null) {
                return null;
            }
            if (parent.getLeaderId() != null && !Objects.equals(parent.getLeaderId(), excludeUserId)) {
                return parent.getLeaderId();
            }
            cur = parent;
        }
        return null;
    }

    /** 供界面展示：某单据的升级记录 */
    public List<FlowEscalation> historyOf(Long documentId) {
        return escalationMapper.selectList(Wrappers.<FlowEscalation>lambdaQuery()
                .eq(FlowEscalation::getDocumentId, documentId)
                .orderByDesc(FlowEscalation::getId));
    }

    /** 读配置用（接口回显"自动升级是否打开"，避免用户以为开着却没生效） */
    public boolean isAutoEnabled() {
        return autoEnabled;
    }
}
