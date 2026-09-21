package com.hxj.oa.document.dto;

import com.hxj.oa.document.entity.Document;
import com.hxj.oa.flow.entity.FlowInstanceNode;
import lombok.Data;

import java.util.List;
import java.util.Map;

/** 单据详情：单据 + 按节点裁剪的表单 + 流转记录 + 附件 */
@Data
public class DocumentDetailVO {

    private Document document;
    private String docTypeName;
    private String flowName;
    private Integer flowVersion;

    /** 当前节点标识（决定字段可见/可编辑） */
    private String viewingNodeKey;
    /** 按 viewingNodeKey 裁剪后的表单 Schema */
    private Map<String, Object> formSchema;

    /** 流转记录（时间线） */
    private List<FlowInstanceNode> flowHistory;
    /** 当前用户在该单据上可执行的动作：approve / reject / countersign / supplement / withdraw */
    private List<String> availableActions;
    /** 当前用户待办的任务 ID（若该单据正在等他处理） */
    private String pendingTaskId;
    /** 当前待办节点是否必须上传办理凭证（前端据此显示「(必填)」并本地拦截） */
    private Boolean requireAttachment;

    private List<AttachmentVO> attachments;
    /** 关联的前置单据/合同（已补齐编号与标题，前端可直接展示） */
    private List<DocumentLinkVO> links;
}
