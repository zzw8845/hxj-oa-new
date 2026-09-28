package com.hxj.oa.document.controller;

import com.hxj.oa.common.annotation.Audit;
import com.hxj.oa.common.api.R;
import com.hxj.oa.common.security.RequirePerm;
import com.hxj.oa.common.security.UserContext;
import com.hxj.oa.document.dto.BatchApproveReq;
import com.hxj.oa.document.dto.BatchApproveResultVO;
import com.hxj.oa.document.service.TodoBatchService;
import com.hxj.oa.document.service.TodoService;
import com.hxj.oa.flow.dto.ApprovalRequest;
import com.hxj.oa.flow.dto.TodoVO;
import com.hxj.oa.flow.entity.FlowInstanceNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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

    /**
     * 我的待办（⚠ 不是分页接口，是「取前 N 条」，返回的是数组不是 PageResult）。
     *
     * @param limit 最多返回几条，默认 100
     */
    @GetMapping
    public R<List<TodoVO>> myTodo(@RequestParam(defaultValue = "100") int limit) {
        return R.ok(todoService.myTodo(UserContext.require(), limit));
    }

    /**
     * 我已处理（⚠ 同上：不是分页接口，返回数组）。
     *
     * @param limit 最多返回几条，默认 50
     */
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
    @RequirePerm("document:approve")
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
    @RequirePerm("document:approve")
    @Audit(module = "flow", action = "batchApprove")
    public R<BatchApproveResultVO> batchApprove(@Valid @RequestBody BatchApproveReq req) {
        BatchApproveResultVO r = todoBatchService.approveAll(req, UserContext.require());
        return R.ok(r, String.format("成功 %d 条，失败 %d 条", r.getSucceeded(), r.getFailed()));
    }

    /** 加签 */
    @PostMapping("/countersign")
    @RequirePerm("document:approve")
    @Audit(module = "flow", action = "countersign")
    public R<Void> countersign(@RequestBody CountersignRequest req) {
        todoService.countersign(req.getTaskId(), req.getUserId(), UserContext.require());
        return R.ok(null, "已加签");
    }

    /**
     * 转办：把一条待办改派给其他人员（**不改审批结论，只改由谁来办**）。
     *
     * <p>门控是 {@code flow:intervene:transfer}，**刻意不是** {@code document:approve} ——
     * 「流程干预动作」与「审批决定动作」是两类职责，合成一个权限点会让"能审批"
     * 自动等于"能改派别人的任务"（详见《管理员角色与权限设计说明》P3）。
     *
     * <p>典型用途：审批人长期请假/调岗，任务压在他名下没人推得动。此时改「节点指派规则」
     * 是没有用的 —— 规则只在任务创建时解析一次，**已经卡住的那张单不受影响**。
     *
     * <p>承办人自己临时不在应走「委托」（`POST /api/delegations`），不是这个接口：
     * 委托可撤销、可限定有效期与业务类别，比把任务永久换人更合适。
     */
    @PostMapping("/transfer")
    @RequirePerm("flow:intervene:transfer")
    @Audit(module = "flow", action = "transfer")
    public R<Void> transfer(@Valid @RequestBody TransferRequest req) {
        todoService.transfer(req.getTaskId(), req.getUserId(), req.getReason(), UserContext.require());
        return R.ok(null, "已转办");
    }

    /** 加签请求 */
    @Data
    public static class CountersignRequest {
        /** 当前待办任务 ID（来自 GET /api/todos 的 taskId） */
        private String taskId;

        /** 要加签进去的用户 ID */
        private Long userId;
    }

    /** 转办请求 */
    @Data
    public static class TransferRequest {
        /** 当前待办任务 ID（来自 GET /api/todos 的 taskId） */
        @NotBlank(message = "任务 ID 不能为空")
        private String taskId;

        /** 转办目标用户 ID（必须是本公司在职人员） */
        @NotNull(message = "请选择转办的目标人员")
        private Long userId;

        /**
         * 转办理由（必填）。
         *
         * <p>它会被写进该节点的审批留痕，并进入审计日志 —— 转办绕过了"谁是审批人"的
         * 节点指派规则（P4：指派规则是唯一事实），所以必须留下"为什么绕过"。
         */
        @NotBlank(message = "请填写转办理由")
        @Size(max = 200, message = "转办理由不能超过 200 字")
        private String reason;
    }
}
