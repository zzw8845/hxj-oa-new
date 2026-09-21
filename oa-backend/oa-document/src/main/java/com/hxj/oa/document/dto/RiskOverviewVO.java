package com.hxj.oa.document.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 风险预警总览。
 *
 * <p>「风险」在本系统里只有一种客观来源：<b>流程节点的处理时限（sla_hours）已经或即将用尽</b>。
 * 时限由 {@code AssigneeTaskListener} 在任务创建时写进 {@code flow_instance_node.deadline}，
 * 这里只做盘点，不重新计算规则 —— 换一套口径就会和待办页的「超期」标记对不上。
 */
@Data
public class RiskOverviewVO {

    /** 已超期节点数 */
    private int overdue;

    /** 即将到期（剩余时间小于阈值）节点数 */
    private int dueSoon;

    /** 进行中节点总数（含无时限的） */
    private int runningTotal;

    /** 无处理时限的进行中节点数 —— 「没有时限」本身是需要披露的风险 */
    private int noDeadline;

    /** 超期 + 临期，前端顶部汇总用 */
    private int riskTotal;

    /** 临期判定阈值（小时），前端展示「24 小时内到期」的说明文案要与它一致 */
    private int dueSoonHours;

    private List<RiskItem> items = new ArrayList<>();

    /** 逐条风险；overdue 项排在前面（由服务层保证顺序） */
    @Data
    public static class RiskItem {

        private Long documentId;
        private String docNo;
        private String title;
        private String businessCategory;
        /** 单据类型名（中文），前端直接展示 */
        private String docTypeName;
        private String applicantName;
        private String deptName;
        private BigDecimal amount;
        /** 单据当前状态，0 草稿 / 1 待审 / 2 审批中 */
        private Integer docStatus;

        private String nodeKey;
        private String nodeName;
        private Long assigneeId;
        private String assigneeName;
        private LocalDateTime deadline;

        /** overdue 已超期 / dueSoon 即将到期 / none 无时限 */
        private String level;

        /** 已超期小时数（level=overdue 时有值） */
        private Long overdueHours;

        /** 剩余小时数（level=dueSoon 时有值） */
        private Long remainHours;
    }
}
