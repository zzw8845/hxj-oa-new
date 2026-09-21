package com.hxj.oa.document.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hxj.oa.common.exception.BizException;
import com.hxj.oa.common.security.DataScopeType;
import com.hxj.oa.common.security.LoginUser;
import com.hxj.oa.common.util.JsonUtils;
import com.hxj.oa.document.entity.Attachment;
import com.hxj.oa.document.entity.Document;
import com.hxj.oa.document.entity.DocumentType;
import com.hxj.oa.document.entity.Notification;
import com.hxj.oa.document.mapper.AttachmentMapper;
import com.hxj.oa.document.mapper.DocumentMapper;
import com.hxj.oa.document.mapper.DocumentTypeMapper;
import com.hxj.oa.document.mapper.NotificationMapper;
import com.hxj.oa.flow.dto.ApprovalRequest;
import com.hxj.oa.flow.dto.TodoVO;
import com.hxj.oa.flow.entity.FlowConfigNode;
import com.hxj.oa.flow.entity.FlowInstanceNode;
import com.hxj.oa.flow.mapper.FlowInstanceNodeMapper;
import com.hxj.oa.flow.service.FlowRuntimeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 待办服务。
 * 待办不是独立数据源，而是「流程节点实例 + 单据」的联合投影，
 * 避免出现「待办和单据状态不一致」这类经典问题。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TodoService {

    private final FlowInstanceNodeMapper instanceNodeMapper;
    private final DocumentMapper documentMapper;
    private final DocumentTypeMapper docTypeMapper;
    private final NotificationMapper notificationMapper;
    private final AttachmentMapper attachmentMapper;
    private final FlowRuntimeService flowRuntimeService;

    /** 我的待办 */
    public List<TodoVO> myTodo(LoginUser user, int limit) {
        List<FlowInstanceNode> nodes = instanceNodeMapper.selectTodoByAssignee(user.getUserId());
        if (nodes.isEmpty()) {
            return List.of();
        }
        List<TodoVO> result = new ArrayList<>();
        Map<Long, String> docTypeNames = new HashMap<>();
        int count = 0;
        for (FlowInstanceNode n : nodes) {
            if (limit > 0 && count >= limit) {
                break;
            }
            Document doc = documentMapper.selectById(n.getDocumentId());
            if (doc == null || doc.getStatus() == null
                    || (doc.getStatus() != DocumentService.STATUS_WAIT && doc.getStatus() != DocumentService.STATUS_RUNNING)) {
                continue; // 单据已办结，待办视为过期
            }
            // 待办同样受数据范围约束（如按部门的会计只看到本部门）
            if (!visibleByScope(doc, user)) {
                continue;
            }
            TodoVO vo = new TodoVO();
            vo.setDocumentId(doc.getId());
            vo.setDocNo(doc.getDocNo());
            vo.setTitle(doc.getTitle());
            vo.setBusinessCategory(doc.getBusinessCategory());
            vo.setApplicantName(doc.getApplicantName());
            vo.setDeptName(doc.getDeptName());
            vo.setAmount(doc.getAmount());
            vo.setSubmittedAt(doc.getSubmittedAt());
            vo.setDocTypeName(docTypeNames.computeIfAbsent(doc.getDocTypeId(), id -> {
                DocumentType dt = docTypeMapper.selectById(id);
                return dt == null ? null : dt.getName();
            }));

            vo.setInstanceId(n.getInstanceId());
            vo.setNodeKey(n.getNodeKey());
            vo.setNodeName(n.getNodeName());
            vo.setTaskId(n.getTaskId());
            vo.setNodeStatus(n.getStatus());
            vo.setDeadline(n.getDeadline());
            vo.setOverdue(n.getDeadline() != null && n.getDeadline().isBefore(LocalDateTime.now()));

            FlowConfigNode cfgNode = findConfigNode(doc.getDocTypeId(), n.getNodeKey());
            vo.setAllowCountersign(cfgNode != null && cfgNode.getAllowCountersign() != null && cfgNode.getAllowCountersign() == 1);
            vo.setAllowReject(cfgNode == null || cfgNode.getAllowReject() == null || cfgNode.getAllowReject() == 1);
            vo.setRequireAttachment(cfgNode != null && cfgNode.getRequireAttachment() != null && cfgNode.getRequireAttachment() == 1);

            result.add(vo);
            count++;
        }
        return result;
    }

    /** 我已处理过的节点（已办） */
    public List<FlowInstanceNode> myDone(LoginUser user, int limit) {
        return instanceNodeMapper.selectList(Wrappers.<FlowInstanceNode>lambdaQuery()
                .eq(FlowInstanceNode::getAssigneeId, user.getUserId())
                .in(FlowInstanceNode::getStatus, 2, 3, 4)
                .orderByDesc(FlowInstanceNode::getActionAt)
                .last("LIMIT " + Math.max(1, Math.min(limit, 200))));
    }

    /** 执行审批动作（通过/驳回/加签/补料），并同步通知 */
    @Transactional(rollbackFor = Exception.class)
    public FlowInstanceNode approval(ApprovalRequest req, LoginUser user) {
        if ("approve".equalsIgnoreCase(req.getAction())) {
            assertVoucherUploaded(req);
        }
        FlowInstanceNode node = flowRuntimeService.approve(req, user);

        Document doc = documentMapper.selectById(node == null ? null : node.getDocumentId());
        if (doc != null) {
            notifyApplicant(doc, node, user);
        }
        return node;
    }

    /**
     * 办理节点凭证必填的<b>权威校验</b>。
     *
     * <p>界面上那句「（必填）」只是提示，绕过界面直接调接口一样能提交 ——
     * 所以真正的拦截必须在服务端。判定粒度是「该单据 + 该流程节点下至少有一个附件」。
     *
     * <p>节点标识取自引擎侧的任务记录，不采信客户端传参，避免伪造节点绕过校验。
     */
    private void assertVoucherUploaded(ApprovalRequest req) {
        FlowInstanceNode cur = instanceNodeMapper.selectOne(Wrappers.<FlowInstanceNode>lambdaQuery()
                .eq(FlowInstanceNode::getTaskId, req.getTaskId())
                .last("LIMIT 1"));
        if (cur == null || cur.getDocumentId() == null || cur.getNodeKey() == null) {
            // 任务本身有问题时交给 FlowRuntimeService 去报更准确的错
            return;
        }
        Document doc = documentMapper.selectById(cur.getDocumentId());
        if (doc == null) {
            return;
        }
        FlowConfigNode cfg = findConfigNode(doc.getDocTypeId(), cur.getNodeKey());
        if (cfg == null || cfg.getRequireAttachment() == null || cfg.getRequireAttachment() != 1) {
            return;
        }
        Long count = attachmentMapper.selectCount(Wrappers.<Attachment>lambdaQuery()
                .eq(Attachment::getDocumentId, cur.getDocumentId())
                .eq(Attachment::getNodeKey, cur.getNodeKey()));
        if (count == null || count == 0) {
            throw BizException.of("「%s」需要上传办理凭证后才能通过，请先上传附件",
                    cfg.getNodeName() == null ? cur.getNodeKey() : cfg.getNodeName());
        }
    }

    /** 加签：把指定用户加入当前任务候选池 */
    @Transactional(rollbackFor = Exception.class)
    public void countersign(String taskId, Long targetUserId, LoginUser user) {
        if (targetUserId == null) {
            throw new BizException("加签人不能为空");
        }
        ApprovalRequest req = new ApprovalRequest();
        req.setTaskId(taskId);
        req.setAction("countersign");
        req.setCountersignUserId(targetUserId);
        req.setComment("加签：" + targetUserId);
        flowRuntimeService.countersign(taskId, targetUserId, user);
    }

    private void notifyApplicant(Document doc, FlowInstanceNode node, LoginUser operator) {
        Notification n = new Notification();
        n.setCompanyId(doc.getCompanyId());
        n.setReceiverId(doc.getApplicantId());
        n.setNotifyType("result");
        n.setBizType("document");
        n.setBizId(doc.getId());
        n.setChannel("inner");
        n.setIsRead(0);
        if (node != null && node.getStatus() != null && node.getStatus() == 3) {
            n.setTitle("您的单据被驳回");
            n.setContent("单据 " + doc.getDocNo() + " 在「" + node.getNodeName() + "」被驳回："
                    + (node.getCommentText() == null ? "无意见" : node.getCommentText()));
        } else if (doc.getStatus() != null && doc.getStatus() == DocumentService.STATUS_APPROVED) {
            n.setTitle("您的单据已通过");
            n.setContent("单据 " + doc.getDocNo() + " 已审批通过");
        } else {
            n.setTitle("您的单据有新进展");
            n.setContent("单据 " + doc.getDocNo() + " 在「"
                    + (node == null ? "-" : node.getNodeName()) + "」由 "
                    + (operator == null ? "-" : operator.getRealName()) + " 处理");
        }
        notificationMapper.insert(n);
    }

    private FlowConfigNode findConfigNode(Long docTypeId, String nodeKey) {
        DocumentType dt = docTypeMapper.selectById(docTypeId);
        if (dt == null || dt.getFlowConfigId() == null) {
            return null;
        }
        return flowRuntimeService.nodesOf(dt.getFlowConfigId()).stream()
                .filter(n -> nodeKey != null && nodeKey.equals(n.getNodeKey()))
                .findFirst().orElse(null);
    }

    private boolean visibleByScope(Document doc, LoginUser user) {
        if (user.hasRole("ADMIN")) {
            return true;
        }
        DataScopeType scope = user.getDataScope() == null ? DataScopeType.SELF : user.getDataScope();
        return switch (scope) {
            case COMPANY -> Objects.equals(doc.getCompanyId(), user.getCompanyId());
            case DEPT, CENTER, CUSTOM_DEPT -> Objects.equals(doc.getDeptId(), user.getDeptId())
                    || (user.getDeptPath() != null && user.getDeptPath().length() > 1);
            case SELF -> Objects.equals(doc.getApplicantId(), user.getUserId());
        };
    }
}
