package com.hxj.oa.document.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 审计日志。
 * 只插入、不更新、不删除（无 updated_at / deleted），因此不继承 BaseEntity。
 * 生产环境该表按月分区，见 sql/schema_partition.sql。
 */
@Data
@TableName("audit_log")
public class AuditLog implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long companyId;
    private Long userId;
    private String userName;
    /** document / flow / permission / seal */
    private String module;
    /** create / submit / approve / reject / config */
    private String action;
    private Long bizId;
    private String detail;
    private String ip;
    private String userAgent;
    private LocalDateTime createdAt;
}
