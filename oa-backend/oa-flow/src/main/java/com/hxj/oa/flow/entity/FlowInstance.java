package com.hxj.oa.flow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hxj.oa.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 流程实例（Flowable 引擎状态的业务投影） */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("flow_instance")
public class FlowInstance extends BaseEntity {

    private Long documentId;
    private Long flowConfigId;
    /** 流程定义版本快照 */
    private Integer flowConfigVersion;
    /** 1 运行中 2 已完成 3 已终止 */
    private Integer status;
    private String currentNodeKey;

    /** Flowable 流程实例 ID —— 业务表与引擎 ACT_RU_* 表的唯一桥接键 */
    private String procInstId;
    /** Flowable 业务键（= document.doc_no） */
    private String businessKey;
    /**
     * 业务类别快照（提交时从单据带过来）。
     *
     * <p>存一份是为了**授权侧能拿到类别**：审批委托可以限定"只代某类单据"，
     * 判定时要拿单据的类别去比。oa-flow 不能反向依赖 oa-document（依赖方向是
     * oa-document → oa-flow），所以把类别随实例一起落库，授权代码自己就能读到，
     * 也不必由调用方传进来 —— 传参的写法一旦有人漏传，类别限制会被静默放宽。
     */
    private String bizCategory;

    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
}
