package com.hxj.oa.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hxj.oa.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 用户兼岗 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user_post")
public class UserPost extends BaseEntity {

    private Long userId;
    private Long postId;
    private Long deptId;
    private Integer isPrimary;
    private Long createdBy;
    private Long updatedBy;
}
