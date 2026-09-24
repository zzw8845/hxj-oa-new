package com.hxj.oa.document.dto;

import lombok.Data;

/** 用印台账查询条件 */
@Data
public class SealQuery {

    /** 用章类型（字典 seal_type：OFFICIAL/CONTRACT/LEGAL/FINANCE/INVOICE） */
    private String sealType;

    /** 归还状态 0 待用印 1 已用印 2 已归还；null = 不限 */
    private Integer returnStatus;

    /** 关键字：匹配用印项目 */
    private String keyword;

    /** 创建时间区间（含端点），格式 yyyy-MM-dd */
    private String dateFrom;
    /** 创建时间上限（含端点），格式 yyyy-MM-dd */
    private String dateTo;

    /** 页码，从 1 开始，默认 1 */
    private Integer pageNum = 1;
    /** 每页条数，默认 20 */
    private Integer pageSize = 20;
}
