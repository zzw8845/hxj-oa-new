package com.hxj.oa.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hxj.oa.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 岗位 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("post")
public class Post extends BaseEntity {

    private Long companyId;
    private String code;
    private String name;
    private Integer status;
    private Integer sortNo;
    private Long createdBy;
    private Long updatedBy;
}
