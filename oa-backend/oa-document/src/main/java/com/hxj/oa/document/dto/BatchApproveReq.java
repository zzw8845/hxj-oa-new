package com.hxj.oa.document.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 批量审批请求。
 *
 * <p><b>刻意只支持「通过」，不提供批量驳回</b>：驳回需要单独的理由与退回目标
 * （上一节点 / 退回发起人 / 指定节点），批量操作很容易误伤一整批单据，
 * 而事后逐条纠正的成本远高于逐条驳回的成本。要驳回请走单条接口。
 *
 * <p>没有 {@code action} 字段也是这个意思 —— 让"这个接口只能通过"成为类型层面的事实，
 * 而不是运行时才校验的约定。
 */
@Data
public class BatchApproveReq {

    @NotEmpty(message = "请至少选择一条待办")
    @Size(max = 50, message = "一次最多处理 50 条")
    private List<String> taskIds;

    /** 统一审批意见（可选，会写进每条任务的审批记录） */
    @Size(max = 500, message = "审批意见不能超过 500 字")
    private String comment;
}
