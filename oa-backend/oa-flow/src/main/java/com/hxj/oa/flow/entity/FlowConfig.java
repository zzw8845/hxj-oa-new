package com.hxj.oa.flow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hxj.oa.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 流程定义（业务配置层）。
 * 这是业务方视角的「流程模板」，Flowable 是实现引擎；
 * flow_config_node + flow_node_assignee 是配置源，bpmn_xml 是生成的引擎产物。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("flow_config")
public class FlowConfig extends BaseEntity {

    /** 所属公司 ID；null = 全局配置 */
    private Long companyId;
    /** 单据类型 ID —— 流程是「按单据类型」生效的 */
    private Long docTypeId;
    /** 流程名称 */
    private String name;
    /** DAILY / BIZ / REIMBURSE / SEAL */
    private String category;
    /** 版本号：单据提交时快照，在途单据不受配置变更影响 */
    private Integer version;
    /** 0 草稿 1 生效 2 废弃 */
    private Integer status;
    /** 生效开始时间；null = 立即生效 */
    private LocalDateTime effectiveFrom;
    /** 生效结束时间；null = 长期有效 */
    private LocalDateTime effectiveTo;

    /** flowable / native */
    private String engineType;
    /** Flowable 流程定义 KEY，如 DAILY_PAYMENT_V1（每次改结构都会新建版本） */
    private String procDefKey;
    /** Flowable 流程定义 ID（形如 key:version:id），部署后回填 */
    private String procDefId;
    /** Flowable 部署 ID */
    private String deploymentId;
    /**
     * BPMN 2.0 XML 快照（由 flow_config_node 生成，可重部署 / 可审计）。
     * ⚠ 内容很大（每份可能几十 KB）。{@code GET /api/flows/configs} 目前**会带上该字段**，
     * 前端列表渲染前请丢弃它，别塞进表格 / 本地缓存。
     */
    private String bpmnXml;
    /** 部署状态 0 未部署 1 已部署 2 部署失败 */
    private Integer deployStatus;
    /** 部署失败原因；成功时为 null */
    private String deployMessage;

    /** 创建人 ID */
    private Long createdBy;
    /** 最后更新人 ID */
    private Long updatedBy;
}
