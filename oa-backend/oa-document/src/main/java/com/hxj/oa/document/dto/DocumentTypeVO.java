package com.hxj.oa.document.dto;

import lombok.Data;

/**
 * 单据类型视图对象。
 *
 * <p>与实体 {@code DocumentType} 的差别是多一个 {@code categoryLabel}：
 * 业务类型的中文标签由**后端**下发，前端不再维护「DAILY→日常付款」这类
 * 写死映射（映射写错/漏加新类别时，前端无从得知，显示就会开天窗）。
 */
@Data
public class DocumentTypeVO {

    private Long id;
    private Long companyId;
    private String code;
    private String name;
    /** 业务大类编码：DAILY / BIZ / REIMBURSE / SEAL */
    private String category;
    /** 业务大类中文标签（后端下发；未知编码时原样返回编码值，前端可直接渲染） */
    private String categoryLabel;
    private Integer mustLinkPrev;
    private Integer status;
    private Integer sortNo;
}
