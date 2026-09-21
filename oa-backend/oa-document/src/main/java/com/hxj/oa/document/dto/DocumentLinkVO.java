package com.hxj.oa.document.dto;

import com.hxj.oa.document.entity.DocumentLink;
import lombok.Data;

/**
 * 单据关联的展示对象。
 *
 * <p>{@code document_link} 里只存 {@code linked_id}（关联单据的主键）。
 * 前端直接渲染数字主键对用户毫无意义 —— 界面上要看到的是
 * 「FK202609180001 采购付款申请」这样的编号 + 标题。
 * 所以在服务端一次性把编号/标题补上，而不是让前端再按 id 逐个回查
 * （N 次请求，而且前端拿不到他人单据的详情时会静默失败）。
 */
@Data
public class DocumentLinkVO {

    private Long id;
    private Long linkedId;
    /** 被关联单据的编号 */
    private String linkedDocNo;
    /** 被关联单据的标题 */
    private String linkedTitle;
    /** prev_doc 前置单据 / contract 合同 */
    private String linkType;
    /** 被关联单据的状态（前端可据此提示「关联的不是已通过单据」） */
    private Integer linkedStatus;

    public static DocumentLinkVO of(DocumentLink link, String docNo, String title, Integer status) {
        DocumentLinkVO vo = new DocumentLinkVO();
        vo.setId(link.getId());
        vo.setLinkedId(link.getLinkedId());
        vo.setLinkType(link.getLinkType());
        vo.setLinkedDocNo(docNo);
        vo.setLinkedTitle(title);
        vo.setLinkedStatus(status);
        return vo;
    }
}
