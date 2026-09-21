package com.hxj.oa.flow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hxj.oa.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 节点指派规则。节点不挂「允许角色」，而挂「指派规则」，
 * 运行时按单据上下文解析出真正的处理人。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("flow_node_assignee")
public class FlowNodeAssignee extends BaseEntity {

    private Long nodeId;
    /**
     * initiator_leader 发起人主管 / dept_role 部门+角色 / biztype_role 业务类型+角色
     * role 指定角色 / user 指定人 / condition 条件触发
     */
    private String ruleType;
    /** 规则参数 JSON，如 {"roleCode":"ACCOUNTANT","fallback":"company"} */
    private String ruleValue;
    /** 1 或签 2 会签 3 依次审批 */
    private Integer signMode;
    private Integer sortNo;
}
