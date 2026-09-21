package com.hxj.oa.document.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hxj.oa.common.api.PageResult;
import com.hxj.oa.common.exception.BizException;
import com.hxj.oa.common.security.LoginUser;
import com.hxj.oa.document.entity.Notification;
import com.hxj.oa.document.mapper.NotificationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 站内通知：读自己的通知、未读数、标记已读。
 *
 * <p>写入侧在 {@code TodoService.notifyApplicant}（审批动作后给发起人留言），
 * 本类只负责读取侧。**所有查询都必须带 receiver_id = 当前用户**，
 * 唯一例外是 Admin 场景，目前不存在，因此不做任何放开。
 *
 * <p>注意：通知不做行级数据范围判定 —— 数据范围管的是「单据可见性」，
 * 而通知天然只属于收件人本人，按 receiver_id 过滤即可，绕过范围判定是正确的。
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    public static final int UNREAD = 0;

    private final NotificationMapper notificationMapper;

    /** 我的通知（最新在前） */
    public PageResult<Notification> mine(Integer pageNum, Integer pageSize, LoginUser user) {
        int num = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int size = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 100);
        IPage<Notification> page = notificationMapper.selectPage(new Page<>(num, size),
                Wrappers.<Notification>lambdaQuery()
                        .eq(Notification::getCompanyId, user.getCompanyId())
                        .eq(Notification::getReceiverId, user.getUserId())
                        .orderByDesc(Notification::getId));
        return PageResult.of(page.getTotal(), num, size, page.getRecords());
    }

    /** 我的未读数（菜单角标用） */
    public long unreadCount(LoginUser user) {
        return notificationMapper.selectCount(Wrappers.<Notification>lambdaQuery()
                .eq(Notification::getCompanyId, user.getCompanyId())
                .eq(Notification::getReceiverId, user.getUserId())
                .eq(Notification::getIsRead, UNREAD));
    }

    /**
     * 标记单条已读。
     *
     * <p>别人的通知一律按「不存在」处理（forbidden），而不是先查出来再比对
     * ——否则可以用 id 探测出「这个 id 存不存在」，等于泄露了他人的数据边界。
     */
    public void markRead(Long id, LoginUser user) {
        Notification n = notificationMapper.selectById(id);
        if (n == null || !user.getUserId().equals(n.getReceiverId())) {
            throw BizException.forbidden("通知不存在或无权操作");
        }
        Notification upd = new Notification();
        upd.setId(id);
        upd.setIsRead(1);
        upd.setReadAt(java.time.LocalDateTime.now());
        notificationMapper.updateById(upd);
    }

    /** 全部标记已读，返回实际影响条数 */
    public int markAllRead(LoginUser user) {
        return notificationMapper.update(null, Wrappers.<Notification>lambdaUpdate()
                .eq(Notification::getCompanyId, user.getCompanyId())
                .eq(Notification::getReceiverId, user.getUserId())
                .eq(Notification::getIsRead, UNREAD)
                .set(Notification::getIsRead, 1)
                .set(Notification::getReadAt, java.time.LocalDateTime.now()));
    }
}
