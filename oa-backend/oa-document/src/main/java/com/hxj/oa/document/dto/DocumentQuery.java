package com.hxj.oa.document.dto;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 单据列表查询条件 */
@Data
public class DocumentQuery {

    /** 单据类型 ID */
    private Long docTypeId;
    /** 业务大类编码（DAILY / BIZ / REIMBURSE / SEAL，标签见 GET /api/document-types/categories） */
    private String businessCategory;
    /** 0 草稿 1 待审批 2 审批中 3 已通过 4 已驳回 5 已撤回 6 已归档；null = 不限 */
    private Integer status;
    /**
     * 多值状态（GET 传 {@code statusList=3,6} 即可，Spring 按逗号拆分）。
     * 台账页「已通过+已归档」、快捷视图「审批中+待审批」都需要多值 ——
     * 单值 status 满足不了，前端就只能拉 200 条本地筛，"搜不全"问题由此而来。
     * 与 {@link #status} 同时给时取并集语义由 service 保证（各自 in/eq，互不冲突）。
     */
    private List<Integer> statusList;
    /** 申请人姓名模糊（台账页专用筛选；搜索框的"申请人"走 keyword） */
    private String applicant;
    /** 申请部门精确（页面下拉给的是部门名） */
    private String department;
    /** 单据编号模糊（台账页"单据编号"筛选；like doc_no，不会误中标题/申请人） */
    private String docNo;
    /** 台账按「归档/最后更新时间」筛 —— 与列表列、导出口径一致 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime updatedAtFrom;
    /** 更新时间上限（ISO-8601，如 2026-09-30T23:59:59） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime updatedAtTo;
    /** 关键字（模糊匹配标题 / 单据编号 / 申请人等） */
    private String keyword;
    /** 金额下限（含） */
    private BigDecimal amountFrom;
    /** 金额上限（含） */
    private BigDecimal amountTo;
    /** mine 我发起的 / todo 待我处理 / done 我已处理 / all 全部（按数据范围） */
    private String scope = "all";

    /** 页码，从 1 开始，默认 1 */
    private Integer pageNum = 1;
    /** 每页条数，默认 20 */
    private Integer pageSize = 20;
}
