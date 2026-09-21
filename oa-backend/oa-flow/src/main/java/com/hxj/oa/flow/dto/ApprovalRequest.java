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
     * 驳回目标：
     *  PREV 上一节点 / START 退回发起人 / nodeKey 指定节点
     */
    private String rejectTo;

    /** 加签：把某人加入当前任务候选人 */
    private Long countersignUserId;

    /** 要求补充的材料说明（会退回发起人并标记待补材料） */
    private String materials;
}
