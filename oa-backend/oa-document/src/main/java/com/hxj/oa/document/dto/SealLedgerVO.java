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
    /** 关联单据 ID */
    private Long documentId;
    /** 单据编号 */
    private String docNo;
    /** 单据标题 */
    private String title;
    /** 申请人姓名 */
    private String applicantName;
    /** 申请部门名称 */
    private String deptName;

    /** 用印项目（盖什么文件 / 什么用途） */
    private String sealProject;
    /** 用章类型编码（字典 seal_type） */
    private String sealType;
    /** 用章类型中文名（来自字典 seal_type），前端直接展示 */
    private String sealTypeName;
    /** 用印事由 */
    private String sealReason;
    /** 用印文件名 */
    private String fileName;

    /** 用印时间 */
    private LocalDateTime sealTime;
    /** 0 待用印 1 已用印 2 已归还 */
    private Integer returnStatus;
    /** 归还状态中文名（后端下发，前端直接展示） */
    private String returnStatusText;
    /** 归还时间 */
    private LocalDateTime returnAt;
    /** 申请创建时间 */
    private LocalDateTime createdAt;

    /** 用印/归还动作台账，**仅详情接口填充**（列表接口为 null，避免 N+1 拉一堆动作） */
    private List<SealRecord> records;
}
