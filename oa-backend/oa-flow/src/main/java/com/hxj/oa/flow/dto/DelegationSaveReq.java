package com.hxj.oa.flow.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 新建审批委托。**委托人固定为当前登录用户**，请求里没有 delegatorId 字段 ——
 * 让别人替你设置"我把权限交给谁"本身就是越权，用字段缺失来表达比运行时校验更硬。
 */
@Data
public class DelegationSaveReq {

    /** 受托人（代理人）用户 ID */
    @NotNull(message = "受托人不能为空")
    private Long delegateId;

    /** 限定单据业务类别（SEAL/DAILY/REIMBURSE...）；留空 = 全部 */
    @Size(max = 32, message = "业务类别不能超过 32 字")
    private String bizCategory;

    /** 生效开始时间（ISO-8601，如 2026-09-24T09:00:00） */
    @NotNull(message = "生效开始时间不能为空")
    private LocalDateTime startAt;

    /** 生效结束时间（ISO-8601） */
    @NotNull(message = "生效结束时间不能为空")
    private LocalDateTime endAt;

    /** 说明 / 委托原因 */
    @Size(max = 255, message = "说明不能超过 255 字")
    private String remark;
}
