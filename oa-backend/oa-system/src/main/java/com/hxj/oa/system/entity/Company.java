package com.hxj.oa.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hxj.oa.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 公司 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("company")
public class Company extends BaseEntity {

    private String code;
    private String name;
    private String shortName;
    private Integer status;
    private Integer sortNo;
    private Long createdBy;
    private Long updatedBy;
}
