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
