package com.hxj.oa.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 基础实体：所有业务表共有的审计字段。
 * created_at / updated_at 交给数据库默认值（DEFAULT CURRENT_TIMESTAMP / ON UPDATE）维护，
 * 应用层不显式赋值，避免与 DB 时钟不一致。
 * deleted 为逻辑删除字段，由 MyBatis-Plus 自动追加 deleted = 0 条件。
 */
@Data
public abstract class BaseEntity implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField(value = "created_at", updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
