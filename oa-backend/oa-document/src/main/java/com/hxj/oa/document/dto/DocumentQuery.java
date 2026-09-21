package com.hxj.oa.document.dto;

import lombok.Data;

import java.math.BigDecimal;

/** 单据列表查询条件 */
@Data
public class DocumentQuery {

    private Long docTypeId;
    private String businessCategory;
    /** 0 草稿 1 待审批 2 审批中 3 已通过 4 已驳回 5 已撤回 6 已归档；null = 不限 */
    private Integer status;
    private String keyword;
    private BigDecimal amountFrom;
    private BigDecimal amountTo;
    /** mine 我发起的 / todo 待我处理 / done 我已处理 / all 全部（按数据范围） */
    private String scope = "all";

    private Integer pageNum = 1;
    private Integer pageSize = 20;
}
