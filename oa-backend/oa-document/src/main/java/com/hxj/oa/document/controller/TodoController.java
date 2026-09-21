package com.hxj.oa.document.controller;

import com.hxj.oa.common.annotation.Audit;
import com.hxj.oa.common.api.R;
import com.hxj.oa.common.security.UserContext;
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
