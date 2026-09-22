package com.hxj.oa.flow.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 委托条目（带出姓名，便于界面直接展示） */
@Data
public class DelegationVO {

    private Long id;
    private Long delegatorId;
    private String delegatorName;
    private Long delegateId;
    private String delegateName;
    private String bizCategory;
    private String bizCategoryText;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    /** 1 生效 0 已撤销 */
    private Integer status;
    private String remark;
    /** 此刻是否在有效期内（含 status=1） */
    private boolean active;
    private LocalDateTime createdAt;
}
