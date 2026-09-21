package com.hxj.oa.flow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 流程新增 / 修改请求。
 *
 * <p>两种提交形态：
 * <ul>
 *   <li><b>简化形态</b>：只给 {@code nodes}（节点名称数组），后端按节点模板库翻译成类型与指派规则。
 *       这是联调版原型当前用的方式。</li>
 *   <li><b>结构化形态</b>：给 {@code nodeItems}，每个节点显式带上 nodeType / ruleType / ruleValue。
 *       将来在前端直接拖拽配审批人时用这个，优先级高于 nodes。</li>
 * </ul>
 */
@Data
public class FlowSaveReq {

    @NotBlank(message = "流程名称不能为空")
    @Size(max = 64, message = "流程名称不能超过 64 字")
    private String name;

    /** DAILY / BIZ / REIMBURSE / SEAL，也兼容前端传「日常审批」「业务单据」；留空则跟随单据类型 */
    private String category;

    /** 关联的单据类型（必填：flow_config.doc_type_id 非空，且流程定义 KEY 由它拼出） */
    private Long docTypeId;

    /** 节点名称列表，按审批顺序排列 */
    private List<String> nodes;

    /** 结构化节点定义，优先级高于 nodes */
    private List<NodeItem> nodeItems;

    @Data
    public static class NodeItem {
        private String nodeName;
        private Integer nodeType;
        private String ruleType;
        private String ruleValue;
        private Integer signMode;
        private Double slaHours;
        private Boolean allowCountersign;
        /** 该节点办理是否必须上传凭证；留空则按节点类型推默认（办理节点为真） */
        private Boolean requireAttachment;
    }
}
