package com.hxj.oa.document.dto;

import com.hxj.oa.document.entity.Document;
import com.hxj.oa.flow.entity.FlowInstanceNode;
import lombok.Data;

import java.util.List;
import java.util.Map;

/** 单据详情：单据 + 按节点裁剪的表单 + 流转记录 + 附件 */
@Data
public class DocumentDetailVO {

    /** 单据本体（含 status 状态机字段，⚠ formData 在这里是 JSON **字符串**，前端要 JSON.parse 一次） */
    private Document document;
    /** 单据类型名称 */
    private String docTypeName;
    /** 流程名称（定位「跑的是哪一版流程」请用这个字段匹配流程配置） */
    private String flowName;
    /** ⚠ 字段名是「流程版本」，实际来源是**表单模板版本**（doc.formTemplateVer）—— 不要拿它去比对流程配置的版本 */
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

    /** 附件列表 */
    private List<AttachmentVO> attachments;
    /** 关联的前置单据/合同（已补齐编号与标题，前端可直接展示） */
    private List<DocumentLinkVO> links;
}
