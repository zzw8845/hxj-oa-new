package com.hxj.oa.flow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hxj.oa.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 审批委托：我不在时由谁代办。
 *
 * <p><b>生效方式是「待办可见 + 审批放行」，不改写流程的指派结果</b>：
 * 节点的 {@code assignee_id} 仍是原承办人，流程历史里"谁审的"不会被换人；
 * 受托人凭这张表获得"看见并处理该待办"的资格。
 * 代价是委托不影响流程侧的分支/条件判断（那些依赖 assignee），
 * 但这比"指派被改写后历史记录里承办人含义变味"要好解释得多。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("flow_delegation")
public class FlowDelegation extends BaseEntity {

    private Long companyId;
    /** 委托人（原承办人） */
    private Long delegatorId;
    /** 受托人（代办人） */
    private Long delegateId;
    /** 限定单据业务类别；NULL = 全部 */
    private String bizCategory;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    /** 1 生效 0 已撤销 */
    private Integer status;
    private String remark;
    private Long createdBy;
    private Long updatedBy;
}
