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

    /** 公司 ID */
    private Long companyId;
    /** 接收人用户 ID；只有本人能查自己的通知 */
    private Long receiverId;
    /** 通知标题 */
    private String title;
    /** 通知正文 */
    private String content;
    /** todo 待办 / cc 抄送 / risk 风险 / result 结果 */
    private String notifyType;
    /** 业务类型 document / seal */
    private String bizType;
    /** 业务对象 ID，配合 bizType 跳转详情 */
    private Long bizId;
    /** inner 站内 / mail 邮件 / sms 短信 */
    private String channel;
    /** 是否已读 1 已读 0 未读 */
    private Integer isRead;
    /** 阅读时间；未读为 null */
    private LocalDateTime readAt;
}
