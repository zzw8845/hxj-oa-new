package com.hxj.oa.document.controller;

import com.hxj.oa.common.api.PageResult;
import com.hxj.oa.common.api.R;
import com.hxj.oa.common.security.UserContext;
import com.hxj.oa.document.entity.Notification;
import com.hxj.oa.document.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 站内通知：读取侧接口。
 *
 * <p>刻意不加 {@code @RequirePerm}：通知是「每个人自己的东西」，
 * 任何登录用户都应能读自己的通知，权限点是给管理动作用的。
 * 归属靠 receiver_id 过滤（见 NotificationService），不靠权限点。
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    /** 我的通知列表（最新在前） */
    @GetMapping
    public R<PageResult<Notification>> mine(@RequestParam(required = false) Integer pageNum,
                                            @RequestParam(required = false) Integer pageSize) {
        return R.ok(notificationService.mine(pageNum, pageSize, UserContext.require()));
    }

    /** 我的未读数（菜单角标） */
    @GetMapping("/unread-count")
    public R<Long> unreadCount() {
        return R.ok(notificationService.unreadCount(UserContext.require()));
    }

    /** 标记单条已读 */
    @PostMapping("/{id}/read")
    public R<Void> read(@PathVariable Long id) {
        notificationService.markRead(id, UserContext.require());
        return R.ok(null, "已读");
    }

    /** 全部标记已读，返回影响条数 */
    @PostMapping("/read-all")
    public R<Integer> readAll() {
        return R.ok(notificationService.markAllRead(UserContext.require()), "已全部标记为已读");
    }
}
