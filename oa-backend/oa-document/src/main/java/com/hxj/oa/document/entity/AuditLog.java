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

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 公司 ID */
    private Long companyId;
    /** 操作人 ID */
    private Long userId;
    /** 操作人姓名 */
    private String userName;
    /** document / flow / permission / seal */
    private String module;
    /** create / submit / approve / reject / config */
    private String action;
    /** 业务对象 ID（如单据 ID）；部分动作无业务对象时为 null */
    private Long bizId;
    /** 变更明细 */
    private String detail;
    /** 客户端 IP */
    private String ip;
    /** 客户端 User-Agent */
    private String userAgent;
    /** 操作时间 */
    private LocalDateTime createdAt;
}
