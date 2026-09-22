package com.hxj.oa.document.dto;

import com.hxj.oa.document.entity.SealRecord;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用印台账行（申请 + 关联单据的可读信息）。
 *
 * <p>刻意把单据的 {@code docNo/title/applicantName} 带出来：印章管理岗看台账时，
 * 关心的是"哪张单、谁申请的、盖什么章、还没还"，而不是一串 id。
 */
@Data
public class SealLedgerVO {

    /** seal_apply.id。**为 null 表示该单据还没有登记过用印**（见 by-document 接口） */
    private Long id;
    private Long documentId;
    private String docNo;
    private String title;
    private String applicantName;
    private String deptName;

    private String sealProject;
    private String sealType;
    /** 用章类型中文名（来自字典 seal_type），前端直接展示 */
    private String sealTypeName;
    private String sealReason;
    private String fileName;

    private LocalDateTime sealTime;
    /** 0 待用印 1 已用印 2 已归还 */
    private Integer returnStatus;
    private String returnStatusText;
    private LocalDateTime returnAt;
    private LocalDateTime createdAt;

    /** 用印/归还动作台账，**仅详情接口填充**（列表接口为 null，避免 N+1 拉一堆动作） */
    private List<SealRecord> records;
}
