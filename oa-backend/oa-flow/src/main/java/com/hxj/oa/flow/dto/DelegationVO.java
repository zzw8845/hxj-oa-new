package com.hxj.oa.flow.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 委托条目（带出姓名，便于界面直接展示） */
@Data
public class DelegationVO {

    /** 委托 ID */
    private Long id;
    /** 委托人（把审批权交出去的人）用户 ID */
    private Long delegatorId;
    /** 委托人姓名 */
    private String delegatorName;
    /** 受托人（替人审批的人）用户 ID */
    private Long delegateId;
    /** 受托人姓名 */
    private String delegateName;
    /** 限定的业务类别编码；null = 全部类别 */
    private String bizCategory;
    /** 业务类别中文名（后端下发，前端直接展示） */
    private String bizCategoryText;
    /** 生效开始时间 */
    private LocalDateTime startAt;
    /** 生效结束时间 */
    private LocalDateTime endAt;
    /** 1 生效 0 已撤销 */
    private Integer status;
    /** 说明 / 委托原因 */
    private String remark;
    /** 此刻是否在有效期内（含 status=1） */
    private boolean active;
    /** 创建时间 */
    private LocalDateTime createdAt;
}
