package com.hxj.oa.document.service;

import com.hxj.oa.common.exception.BizException;
import com.hxj.oa.common.security.LoginUser;
import com.hxj.oa.document.dto.BatchApproveReq;
import com.hxj.oa.document.dto.BatchApproveResultVO;
import com.hxj.oa.flow.dto.ApprovalRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 批量审批。
 *
 * <h3>为什么单独一个类，而不是在 {@code TodoService} 里加个方法</h3>
 * 批量的正确语义是**每条各自一个事务**：一条因"状态已被别人改过"失败，
 * 不该把已成功的几条一起回滚。
 * 而 {@code @Transactional} 是靠 Spring 代理生效的 —— **同类内自调用不走代理**，
 * 在 {@code TodoService} 里写循环调用自己的 {@code approval()}，
 * 事务注解会静默失效：要么全进一个事务（一条失败全回滚），要么压根没事务。
 * 所以这里注入 {@code TodoService}（代理对象），循环里每次调用都是**一次独立事务**。
 * 也因此本类的方法**刻意不加 {@code @Transactional}**。
 *
 * <h3>授权没有放宽</h3>
 * 每条仍然走 {@code FlowRuntimeService#approve} 里那套"你是不是该节点的处理人"的判定；
 * 传别人的 taskId 只会得到该条失败（"您不是该节点的处理人"），不会造成越权。
 *
 * <h3>审计</h3>
 * 每次审批都会写流程历史（谁、何时、什么意见，按 taskId 逐条），这是权威记录；
 * 接口层另记一条 {@code batchApprove}，回答"这一批是谁在什么时候发起的"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TodoBatchService {

    /** 一次请求的上限。也由 DTO 的 @Size 兜一层，这里再挡一次（防绕过校验的调用方） */
    private static final int MAX_BATCH = 50;

    private final TodoService todoService;

    public BatchApproveResultVO approveAll(BatchApproveReq req, LoginUser user) {
        List<String> taskIds = req.getTaskIds() == null ? List.of()
                : req.getTaskIds().stream()
                        .filter(StringUtils::hasText)
                        .map(String::trim)
                        .distinct()
                        .toList();
        if (taskIds.isEmpty()) {
            throw BizException.of("请至少选择一条待办");
        }
        if (taskIds.size() > MAX_BATCH) {
            throw BizException.of("一次最多处理 %d 条（本次 %d 条），请分批提交", MAX_BATCH, taskIds.size());
        }

        BatchApproveResultVO result = new BatchApproveResultVO();
        result.setTotal(taskIds.size());
        String comment = StringUtils.hasText(req.getComment()) ? req.getComment().trim() : "批量通过";

        for (String taskId : taskIds) {
            try {
                ApprovalRequest one = new ApprovalRequest();
                one.setTaskId(taskId);
                one.setAction("approve");
                one.setComment(comment);
                var node = todoService.approval(one, user);   // 走代理 → 独立事务
                result.getItems().add(BatchApproveResultVO.Item.ok(taskId,
                        node == null ? null : node.getDocumentId()));
                result.setSucceeded(result.getSucceeded() + 1);
            } catch (Exception e) {
                // 逐条兜住：一条失败不能中断整批，也不能把已成功的回滚掉。
                // 失败原因原样透出（例如"您不是该节点的处理人""该任务已被处理"），
                // 用户据此能直接判断是自己选错了还是别人先处理了。
                String msg = StringUtils.hasText(e.getMessage()) ? e.getMessage() : e.toString();
                result.getItems().add(BatchApproveResultVO.Item.fail(taskId, msg));
                result.setFailed(result.getFailed() + 1);
                log.info("批量审批单条失败 taskId={} 操作人={} 原因={}", taskId, user.getRealName(), msg);
            }
        }

        log.info("批量审批完成 操作人={} 共 {} 条：成功 {} / 失败 {}",
                user.getRealName(), result.getTotal(), result.getSucceeded(), result.getFailed());
        return result;
    }
}
