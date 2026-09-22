package com.hxj.oa.document.controller;

import com.hxj.oa.common.annotation.Audit;
import com.hxj.oa.common.api.R;
import com.hxj.oa.common.security.UserContext;
import com.hxj.oa.document.dto.BatchApproveReq;
import com.hxj.oa.document.dto.BatchApproveResultVO;
import com.hxj.oa.document.service.TodoBatchService;
import com.hxj.oa.document.service.TodoService;
import com.hxj.oa.flow.dto.ApprovalRequest;
import com.hxj.oa.flow.dto.TodoVO;
import com.hxj.oa.flow.entity.FlowInstanceNode;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 待办与审批动作 */
@RestController
@RequestMapping("/api/todos")
@RequiredArgsConstructor
public class TodoController {

    private final TodoService todoService;
    private final TodoBatchService todoBatchService;

    /** 我的待办 */
    @GetMapping
    public R<List<TodoVO>> myTodo(@RequestParam(defaultValue = "100") int limit) {
        return R.ok(todoService.myTodo(UserContext.require(), limit));
    }

    /** 我已处理 */
    @GetMapping("/done")
    public R<List<FlowInstanceNode>> myDone(@RequestParam(defaultValue = "50") int limit) {
        return R.ok(todoService.myDone(UserContext.require(), limit));
    }

    /**
     * 审批：通过 / 驳回 / 要求补料。
     *
     * <p>三种结局共用 action=approve 这一「类别」，具体是哪种写在审计 detail 的 reqAction 里 ——
     * 审计要回答的是「谁在什么时候对该单据做了审批动作、结果是什么」，两者都需要。
     */
    @PostMapping("/approve")
    @Audit(module = "flow", action = "approve")
    public R<FlowInstanceNode> approve(@Valid @RequestBody ApprovalRequest req) {
        return R.ok(todoService.approval(req, UserContext.require()), "处理成功");
    }

    /**
     * 批量通过（一次最多 50 条）。
     *
     * <p><b>逐条独立事务、逐条返回结果</b>：一条因"状态已被别人改过"失败，
     * 不会把已成功的几条一起回滚 —— "3 条通过、1 条失败"是有用的结果，
     * 全回滚只会让用户白等一遍还得自己找是哪条挡住了。
     *
     * <p><b>只支持通过</b>：驳回需要单独的理由与退回目标，批量很容易误伤一整批。
     *
     * <p>授权没有放宽：每条仍走「你是这个节点的处理人吗」那套判定，
     * 传别人的 taskId 只会得到该条失败。
     */
    @PostMapping("/batch-approve")
    @Audit(module = "flow", action = "batchApprove")
    public R<BatchApproveResultVO> batchApprove(@Valid @RequestBody BatchApproveReq req) {
        BatchApproveResultVO r = todoBatchService.approveAll(req, UserContext.require());
        return R.ok(r, String.format("成功 %d 条，失败 %d 条", r.getSucceeded(), r.getFailed()));
    }

    /** 加签 */
    @PostMapping("/countersign")
    @Audit(module = "flow", action = "countersign")
    public R<Void> countersign(@RequestBody CountersignRequest req) {
        todoService.countersign(req.getTaskId(), req.getUserId(), UserContext.require());
        return R.ok(null, "已加签");
    }

    @Data
    public static class CountersignRequest {
        private String taskId;
        private Long userId;
    }
}
