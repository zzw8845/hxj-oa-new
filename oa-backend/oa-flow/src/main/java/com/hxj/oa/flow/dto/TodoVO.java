package com.hxj.oa.flow.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 待办/已办条目 */
@Data
public class TodoVO {

    /** 单据 ID（点开详情用 GET /api/documents/{id}） */
    private Long documentId;
    /** 单据编号 */
    private String docNo;
    /** 单据类型名称，如「日常付款申请」 */
    private String docTypeName;
    /** 业务大类编码（中文标签取 GET /api/document-types/categories） */
    private String businessCategory;
    /** 单据标题 */
    private String title;
    /** 申请人姓名 */
    private String applicantName;
    /** 申请人部门名称 */
    private String deptName;
    /** 金额（元） */
    private BigDecimal amount;

    /** 流程侧信息 */
    private Long instanceId;
    /** 当前节点编码，如 n2 */
    private String nodeKey;
    /** 当前节点名称，如「直属部门负责人」 */
    private String nodeName;
    /** 审批任务 ID（调 /api/todos/approve、/countersign 时回传） */
    private String taskId;
    /** 节点状态：0 待处理 1 处理中 2 已通过 3 已驳回 4 已跳过 5 已抄送 6 已撤回 */
    private Integer nodeStatus;
    /** 该节点要求完成的时间（可能为 null） */
    private LocalDateTime deadline;
    /** 是否已超时 */
    private boolean overdue;
    /** 提交时间 */
    private LocalDateTime submittedAt;

    /* ---- 审批委托：受托人视角 ---- */
    /** 该待办原本的承办人（代办的场景下与当前登录用户不同） */
    private Long assigneeId;
    /** 代谁办理；null = 这是自己的待办 */
    private String onBehalfOf;
    /**
     * 是否只是「候选人」（既不是我的待办、也不是委托给我的）。
     * 典型来源：**超时升级**把上级加签成候选人 —— 不加这个标记的话，
     * 升级进来的待办会与"自己的待办"长得一模一样，界面无从区分。
     */
    private boolean viaCandidate;
    /** 是否允许加签 */
    private boolean allowCountersign;
    /** 是否允许驳回 */
    private boolean allowReject;
    /** 该节点是否必须上传办理凭证（前端据此决定是否显示「(必填)」并本地拦截） */
    private boolean requireAttachment;
}
