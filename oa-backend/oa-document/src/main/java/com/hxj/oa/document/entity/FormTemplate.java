package com.hxj.oa.document.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hxj.oa.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 表单模板（动态表单 Schema 宿主） */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("form_template")
public class FormTemplate extends BaseEntity {

    private Long companyId;
    private Long docTypeId;
    private String name;
    /** 版本号：单据提交时快照，配置变更不影响在途单据 */
    private Integer version;
    /** 表单 Schema JSON：字段定义 + 显隐规则 + 校验规则 */
    private String schemaJson;
    /** 0 草稿 1 生效 2 废弃 */
    private Integer status;
    private LocalDateTime effectiveFrom;
    private LocalDateTime effectiveTo;
    private Long createdBy;
    private Long updatedBy;
}
