package com.hxj.oa.document.controller;

import com.hxj.oa.common.api.PageResult;
import com.hxj.oa.common.api.R;
import com.hxj.oa.common.security.RequirePerm;
import com.hxj.oa.document.audit.AuditLogService;
import com.hxj.oa.document.entity.AuditLog;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * 审计日志查询（管理端）。
 *
 * <p>只有 {@code system:audit}（当前仅超级管理员角色持有）能读 —— 审计日志里含
 * 全公司的权限变更与数据范围调整记录，可见性必须比业务数据更严。
 *
 * <p>强制分页：审计表是全库唯一无界增长的表。
 */
@RestController
@RequestMapping("/api/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogService auditLogService;

    /**
     * 审计日志分页查询（强制分页，参数全部可选，不传即不过滤）。
     *
     * @param module  模块：document / flow / permission / system / seal / auth
     * @param action  动作名，如 approve、createUser、updateRole
     * @param userId  操作人 ID
     * @param bizId   业务对象 ID（如单据 ID）
     * @param keyword 关键字（在 account / 详情文本里模糊匹配）
     * @param from    起始时间（ISO-8601，如 2026-09-01T00:00:00）
     * @param to      截止时间（同上）
     * @param pageNum  页码，从 1 开始；不传默认 1
     * @param pageSize 每页条数；不传默认 20
     */
    @GetMapping
    @RequirePerm("system:audit")
    public R<PageResult<AuditLog>> page(@RequestParam(required = false) String module,
                                       @RequestParam(required = false) String action,
                                       @RequestParam(required = false) Long userId,
                                       @RequestParam(required = false) Long bizId,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false)
                                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                                       @RequestParam(required = false)
                                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
                                       @RequestParam(required = false) Integer pageNum,
                                       @RequestParam(required = false) Integer pageSize) {
        return R.ok(auditLogService.query(module, action, userId, bizId, keyword, from, to, pageNum, pageSize));
    }
}
