package com.hxj.oa.document.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 单据编码序列。
 * 特殊表：无 created_at / deleted（序列不参与逻辑删除），因此不继承 BaseEntity。
 * 取号时用 `UPDATE ... SET current_val = current_val + 1` 的行锁保证同一周期内不重号。
 */
@Data
@TableName("code_sequence")
public class CodeSequence implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long companyId;
    /** 业务前缀 FK 付款 / BX 报销 / YY 用印 */
    private String bizPrefix;
    /** 周期，如 20260918（日） */
    private String period;
    private Long currentVal;
    private LocalDateTime updatedAt;
}
