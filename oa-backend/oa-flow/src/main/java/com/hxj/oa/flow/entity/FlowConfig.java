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

    private Long companyId;
    private Long docTypeId;
    private String name;
    /** DAILY / BIZ / REIMBURSE / SEAL */
    private String category;
    /** 版本号：单据提交时快照，在途单据不受配置变更影响 */
    private Integer version;
    /** 0 草稿 1 生效 2 废弃 */
    private Integer status;
    private LocalDateTime effectiveFrom;
    private LocalDateTime effectiveTo;

    /** flowable / native */
    private String engineType;
    private String procDefKey;
    private String procDefId;
    private String deploymentId;
    private String bpmnXml;
    /** 0 未部署 1 已部署 2 部署失败 */
    private Integer deployStatus;
    private String deployMessage;

    private Long createdBy;
    private Long updatedBy;
}
