package com.hxj.oa.document.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 超时升级执行结果（定时与手动共用同一份统计，便于排查"为什么没升级"） */
@Data
public class EscalationResultVO {

    /** 自动升级开关是否打开（需求未确认前默认关闭；手动触发不受它限制） */
    private boolean autoEnabled;
    /** 本次扫描到的超时节点数 */
    private int scanned;
    /** 已升级（把上级加签进来） */
    private int escalated;
    /** 未找到上级部门负责人（已记台账，不会反复重试） */
    private int noLeader;
    /** 之前已经升级过（幂等跳过） */
    private int alreadyEscalated;
    /** 节点状态与引擎不一致（任务已不存在），跳过并记台账 */
    private int staleTask;
    private List<String> details = new ArrayList<>();
}
