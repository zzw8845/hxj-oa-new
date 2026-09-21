package com.hxj.oa.document.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hxj.oa.common.security.LoginUser;
import com.hxj.oa.common.util.DataScopeHelper;
import com.hxj.oa.document.dto.RiskOverviewVO;
import com.hxj.oa.document.entity.Document;
import com.hxj.oa.document.entity.DocumentType;
import com.hxj.oa.document.mapper.DocumentMapper;
import com.hxj.oa.document.mapper.DocumentTypeMapper;
import com.hxj.oa.flow.entity.FlowInstanceNode;
import com.hxj.oa.flow.mapper.FlowInstanceNodeMapper;
import com.hxj.oa.system.entity.SysUser;
import com.hxj.oa.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 风险预警：盘点「压在审批人手里、时限已经或即将用尽」的节点。
 *
 * <p>三条口径约束，改动前必须一起看：
 * <ol>
 *   <li><b>数据范围用 SQL 侧，不用 Java 侧</b>。这里的行级过滤是
 *       {@link DataScopeHelper} 拼进 QueryWrapper（和单据列表、看板统计完全同一条路径）。
 *       不要改写成 Java 里逐条判断 —— {@code TodoService} 里的
 *       {@code visibleByScope} 是按「待办视角」写的（只看自己名下，故意不按申请人部门收缩），
 *       拿它来筛预警会把「该我审但属于别的部门」的单子漏掉，两个页面口径立刻对不上。</li>
 *   <li><b>必须再用单据状态兜底</b>。节点表里会留下单据被撤回/驳回后的历史行
 *       （实测 4 条 status=0 但单据已撤回），只按节点状态盘点会虚报风险。</li>
 *   <li><b>时限只读不重算</b>。deadline 由流程节点创建时按 {@code sla_hours} 写入，
 *       这里重新按配置算一遍就会和待办页的 overdue 标记不一致。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskService {

    /** 临期阈值（小时）：距离 deadline 不足该时长即计入「即将到期」 */
    public static final int DUE_SOON_HOURS = 24;

    private final FlowInstanceNodeMapper instanceNodeMapper;
    private final DocumentMapper documentMapper;
    private final DocumentTypeMapper docTypeMapper;
    private final SysUserMapper sysUserMapper;

    /** 当前用户可见范围内的风险盘点 */
    public RiskOverviewVO overview(LoginUser user) {
        RiskOverviewVO vo = new RiskOverviewVO();
        vo.setDueSoonHours(DUE_SOON_HOURS);
        vo.setItems(new ArrayList<>());

        List<FlowInstanceNode> nodes = instanceNodeMapper.selectActiveNodes();
        if (nodes.isEmpty()) {
            return vo;
        }

        // 一次把涉及的可见单据捞齐：行级数据范围就落在这个查询上
        Set<Long> docIds = nodes.stream()
                .map(FlowInstanceNode::getDocumentId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, Document> visibleDocs = visibleDocuments(docIds, user);

        Map<Long, String> typeNames = new HashMap<>();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime dueSoonLine = now.plusHours(DUE_SOON_HOURS);

        for (FlowInstanceNode n : nodes) {
            Document doc = visibleDocs.get(n.getDocumentId());
            if (doc == null) {
                // 单据不可见（超出数据范围），或已撤回/驳回/办结 —— 不计入
                continue;
            }
            vo.setRunningTotal(vo.getRunningTotal() + 1);
            if (n.getDeadline() == null) {
                vo.setNoDeadline(vo.getNoDeadline() + 1);
                continue;
            }
            RiskOverviewVO.RiskItem item = toItem(n, doc, typeNames, now, dueSoonLine);
            if (item == null) {
                continue;
            }
            if ("overdue".equals(item.getLevel())) {
                vo.setOverdue(vo.getOverdue() + 1);
            } else {
                vo.setDueSoon(vo.getDueSoon() + 1);
            }
            vo.getItems().add(item);
        }

        // 超期在最前，其余按剩余时间升序 —— 前端不必再排一次
        vo.getItems().sort((a, b) -> a.getDeadline().compareTo(b.getDeadline()));
        vo.setRiskTotal(vo.getOverdue() + vo.getDueSoon());
        fillAssigneeNames(vo.getItems());
        return vo;
    }

    /**
     * 补承办人姓名。
     *
     * <p>节点行上的 {@code assignee_name} 只在**审批动作发生时**由 FlowRuntimeService 写入
     * （实测：全部 93 条节点里 84 条有名字，而在途的 9 条一个都没有）。
     * 结果就是「谁正压着这个件」——风险页最该回答的问题——在库里恰好是空的。
     * 这里按 assignee_id 回查 sys_user 补上，不改审批主链路。
     *
     * <p>根因在 {@code AssigneeTaskListener} 建节点时只写了 assignee_id、没写 assignee_name；
     * 那属于审批链路的改动，需要单独评估（含历史数据是否回填），不在本次范围内。
     */
    private void fillAssigneeNames(List<RiskOverviewVO.RiskItem> items) {
        Set<Long> need = items.stream()
                .filter(i -> (i.getAssigneeName() == null || i.getAssigneeName().isBlank())
                        && i.getAssigneeId() != null)
                .map(RiskOverviewVO.RiskItem::getAssigneeId)
                .collect(Collectors.toSet());
        if (need.isEmpty()) {
            return;
        }
        Map<Long, String> names = sysUserMapper.selectList(Wrappers.<SysUser>lambdaQuery()
                        .in(SysUser::getId, need))
                .stream()
                .collect(Collectors.toMap(SysUser::getId, SysUser::getRealName, (a, b) -> a));
        for (RiskOverviewVO.RiskItem i : items) {
            if ((i.getAssigneeName() == null || i.getAssigneeName().isBlank()) && i.getAssigneeId() != null) {
                i.setAssigneeName(names.get(i.getAssigneeId()));
            }
        }
    }

    /** 与单据列表/看板同一条行级范围：DataScopeHelper 片段 + 单据必须仍在流转中 */
    private Map<Long, Document> visibleDocuments(Set<Long> docIds, LoginUser user) {
        if (docIds.isEmpty()) {
            return Map.of();
        }
        var qw = Wrappers.<Document>lambdaQuery()
                .in(Document::getId, docIds)
                // 只认「待审/审批中」：撤回、驳回、办结的单据即使节点行还挂着也不算风险
                .in(Document::getStatus, DocumentService.STATUS_WAIT, DocumentService.STATUS_RUNNING);
        String scopeClause = DataScopeHelper.buildClause("", user);
        if (scopeClause != null && !scopeClause.isBlank()) {
            qw.apply(scopeClause);
        }
        return documentMapper.selectList(qw).stream()
                .collect(Collectors.toMap(Document::getId, Function.identity(), (a, b) -> a));
    }

    /** 时限已用尽或即将用尽才产出条目；还有充裕时间的节点不进预警列表（但已计入 runningTotal） */
    private RiskOverviewVO.RiskItem toItem(FlowInstanceNode n, Document doc, Map<Long, String> typeNames,
                                           LocalDateTime now, LocalDateTime dueSoonLine) {
        LocalDateTime deadline = n.getDeadline();
        if (deadline.isAfter(dueSoonLine)) {
            return null;
        }
        RiskOverviewVO.RiskItem item = new RiskOverviewVO.RiskItem();
        item.setDocumentId(doc.getId());
        item.setDocNo(doc.getDocNo());
        item.setTitle(doc.getTitle());
        item.setBusinessCategory(doc.getBusinessCategory());
        item.setDocTypeName(typeNames.computeIfAbsent(doc.getDocTypeId(), id -> {
            DocumentType dt = docTypeMapper.selectById(id);
            return dt == null ? null : dt.getName();
        }));
        item.setApplicantName(doc.getApplicantName());
        item.setDeptName(doc.getDeptName());
        item.setAmount(doc.getAmount());
        item.setDocStatus(doc.getStatus());
        item.setNodeKey(n.getNodeKey());
        item.setNodeName(n.getNodeName());
        item.setAssigneeId(n.getAssigneeId());
        item.setAssigneeName(n.getAssigneeName());
        item.setDeadline(deadline);

        if (deadline.isBefore(now)) {
            item.setLevel("overdue");
            item.setOverdueHours(Duration.between(deadline, now).toHours());
        } else {
            item.setLevel("dueSoon");
            item.setRemainHours(Math.max(0, Duration.between(now, deadline).toHours()));
        }
        return item;
    }
}
