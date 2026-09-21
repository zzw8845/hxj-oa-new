package com.hxj.oa.document.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hxj.oa.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 字段级权限（列级）。
 * 约定：查不到记录时 —— 发起节点 n1 全字段可编辑，其余节点只读可见。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("form_field_permission")
public class FormFieldPermission extends BaseEntity {

    private Long templateId;
    /** 节点标识，* 表示所有节点 */
    private String nodeKey;
    private String fieldKey;
    private Integer visible;
    private Integer editable;
}
