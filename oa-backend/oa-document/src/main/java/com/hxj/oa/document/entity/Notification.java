package com.hxj.oa.document.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hxj.oa.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 站内通知 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("notification")
public class Notification extends BaseEntity {

    private Long companyId;
    private Long receiverId;
    private String title;
    private String content;
    /** todo 待办 / cc 抄送 / risk 风险 / result 结果 */
    private String notifyType;
    private String bizType;
    private Long bizId;
    /** inner 站内 / mail 邮件 / sms 短信 */
    private String channel;
    private Integer isRead;
    private LocalDateTime readAt;
}
