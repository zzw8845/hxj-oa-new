package com.hxj.oa.flow.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 审批动作请求 */
@Data
public class ApprovalRequest {

    /** Flowable 任务 ID */
    @NotBlank(message = "任务ID不能为空")
    private String taskId;

    /** approve 通过 / reject 驳回 */
    @NotBlank(message = "审批动作不能为空")
    private String action;

    /** 审批意见 */
    private String comment;

    /**
     * 驳回目标。**只有两种行为**，别按名字想当然：
     * <ul>
     *   <li>不传 / 空 / {@code START} → <b>终止当前流程实例，退回发起人</b>（改后需重新提交）</li>
     *   <li>{@code nodeKey}（如 {@code n3}）→ 用 changeState **跳回该节点**，由该节点审批人重审</li>
     * </ul>
     * ⚠ {@code PREV} 名义上是"上一节点"，但**当前实现与 {@code START} 等价**
     * （见 {@code FlowRuntimeService.doReject}：PREV 与 START 一起被排除在"跳回指定节点"之外）。
     * 需要"退回上一节点"时请传具体 nodeKey，不要传 PREV。
     */
    private String rejectTo;

    /** 加签：把某人加入当前任务候选人 */
    private Long countersignUserId;

    /** 要求补充的材料说明（会退回发起人并标记待补材料） */
    private String materials;
}
