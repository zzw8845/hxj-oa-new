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

    /** 所属公司 ID；null = 全局通用 */
    private Long companyId;
    /** 单据类型 ID */
    private Long docTypeId;
    /** 模板名称 */
    private String name;
    /** 版本号：单据提交时快照，配置变更不影响在途单据 */
    private Integer version;
    /** 表单 Schema JSON：字段定义 + 显隐规则 + 校验规则。⚠ 是字符串，前端要 JSON.parse */
    private String schemaJson;
    /** 0 草稿 1 生效 2 废弃 */
    private Integer status;
    /** 生效开始时间；null = 立即生效 */
    private LocalDateTime effectiveFrom;
    /** 生效结束时间；null = 长期有效 */
    private LocalDateTime effectiveTo;
    /** 创建人 ID */
    private Long createdBy;
    /** 最后更新人 ID */
    private Long updatedBy;
}
