package com.hxj.oa.document.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 单据统计（按当前登录用户的行级数据范围汇总）。
 *
 * <p>status 分组口径与前端展示一一对应，刻意把两个「同类状态」合并，
 * 避免前端再拼一次判断逻辑（两端各写一遍必然漂移）：
 * <ul>
 *   <li>{@code running} = 1 待审 + 2 审批中 → 前端「审批中」</li>
 *   <li>{@code approved} = 3 已通过 + 6 已归档 → 前端「已通过 / 已办结」</li>
 * </ul>
 *
 * <p>这些数字一律来自数据库聚合，不是前端拿「当前这一页列表」数出来的
 * ——列表只加载了一页，按列表数出来的分布是错的（页大小一改就变）。
 */
@Data
public class DocumentStatsVO {

    /** 全部（当前用户可见范围内） */
    private long total;
    /** 0 草稿 */
    private long draft;
    /** 1 待审 + 2 审批中 */
    private long running;
    /** 3 已通过 + 6 已归档 */
    private long approved;
    /** 4 已驳回 */
    private long rejected;
    /** 5 已撤回 */
    private long withdrawn;

    /**
     * 近 7 天每日发起量（含今天，按日期升序）。
     *
     * <p>固定返回 7 条、缺失的日期补 0 —— 前端因此不需要再做一遍日期推算，
     * 两端各写一份日历逻辑迟早会错开一天（时区 / 夏令时 / 月份边界）。
     *
     * <p>口径是 {@code submitted_at}（发起＝提交），与「近7天发起量」卡片文案一致；
     * 草稿未提交因此不计入。
     */
    private List<DailyCount> dailyCounts = new ArrayList<>();

    /** 近 7 天发起量合计 = dailyCounts 各项之和 */
    private long weekTotal;

    /** 单日发起量 */
    @Data
    public static class DailyCount {
        /** yyyy-MM-dd */
        private String date;
        private long count;

        public DailyCount() {
        }

        public DailyCount(String date, long count) {
            this.date = date;
            this.count = count;
        }
    }
}
