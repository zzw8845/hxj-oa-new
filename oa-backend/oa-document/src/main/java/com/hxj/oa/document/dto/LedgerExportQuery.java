package com.hxj.oa.document.dto;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * 台账导出筛选条件。
 *
 * <p>与「台账档案」页面的筛选栏一一对应。台账口径固定为**已通过 / 已归档**的单据
 * （页面上写的「仅归档全部审批通过的单据」），因此条件里没有 status —— 不然前端能传个
 * status=0，导出一份满是草稿的「台账」。
 */
@Data
public class LedgerExportQuery {

    /** 申请人姓名，模糊匹配 */
    private String applicant;

    /** 申请部门名称，精确匹配（页面上的下拉来自部门清单） */
    private String department;

    /** 单据编号，模糊匹配 */
    private String docNo;

    /** 归档时间起（含当天）—— 与台账列表「归档时间」列取的是同一个字段（updated_at） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dateFrom;

    /** 归档时间止（含当天，内部按次日 0 点开区间处理） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dateTo;
}
