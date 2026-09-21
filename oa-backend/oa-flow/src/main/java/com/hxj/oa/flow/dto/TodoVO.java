package com.hxj.oa.flow.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 待办/已办条目 */
@Data
public class TodoVO {

    private Long documentId;
    private String docNo;
    private String docTypeName;
    private String businessCategory;
    private String title;
    private String applicantName;
    private String deptName;
    private BigDecimal amount;

    /** 流程侧信息 */
    private Long instanceId;
    private String nodeKey;
    private String nodeName;
    private String taskId;
    /** 0 待处理 1 处理中 2 已通过 3 已驳回 */
    private Integer nodeStatus;
    private LocalDateTime deadline;
    /** 是否已超时 */
    private boolean overdue;
    private LocalDateTime submittedAt;
    /** 是否允许加签 / 驳回 */
    private boolean allowCountersign;
    private boolean allowReject;
    /** 该节点是否必须上传办理凭证（前端据此决定是否显示「(必填)」并本地拦截） */
    private boolean requireAttachment;
}
