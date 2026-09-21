package com.hxj.oa.document.audit;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hxj.oa.common.api.PageResult;
import com.hxj.oa.document.entity.AuditLog;
import com.hxj.oa.document.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 审计日志的写入与查询。
 *
 * <p>写入用 {@code REQUIRES_NEW}：审计行必须在**独立事务**里提交。
 * 若跟随业务事务，一旦业务回滚（比如审批被业务规则拒绝），最需要留痕的那条「失败尝试」会一起消失，
 * 审计就只剩下成功记录 —— 那是最没有价值的一半。
 *
 * <p>写入失败**不得**影响业务：本方法自己吞掉异常，切面外层还有第二道兜底。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    /** user_name 列 VARCHAR(32)，超长会直接抛 SQL 异常把审计写挂 */
    private static final int USER_NAME_MAX = 32;
    /** user_agent 列 VARCHAR(255) */
    private static final int UA_MAX = 255;
    /** module / action 列 VARCHAR(64) */
    private static final int CODE_MAX = 64;
    /** ip 列 VARCHAR(64) */
    private static final int IP_MAX = 64;

    private final AuditLogMapper auditLogMapper;

    /**
     * 落一条审计。独立事务提交；任何异常都在此消化，不向上抛。
     *
     * @return 是否写入成功（供测试与排障断言，业务方无需关心）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean record(AuditLog entry) {
        if (entry == null) {
            return false;
        }
        try {
            entry.setUserName(truncate(entry.getUserName(), USER_NAME_MAX));
            entry.setUserAgent(truncate(entry.getUserAgent(), UA_MAX));
            entry.setModule(truncate(entry.getModule(), CODE_MAX));
            entry.setAction(truncate(entry.getAction(), CODE_MAX));
            entry.setIp(truncate(entry.getIp(), IP_MAX));
            auditLogMapper.insert(entry);
            return true;
        } catch (Exception e) {
            // 审计失败不能反噬业务：只记 error，绝不上抛
            log.error("审计日志写入失败 module={} action={} bizId={}: {}",
                    entry.getModule(), entry.getAction(), entry.getBizId(), e.toString());
            return false;
        }
    }

    /**
     * 分页查询审计日志（管理端「审计日志」页用）。
     *
     * <p>必须分页：审计表是全库唯一**无界增长**的表，任何一次全量查询都会随运行时间越来越慢。
     */
    public PageResult<AuditLog> query(String module, String action, Long userId, Long bizId,
                                     String keyword, LocalDateTime from, LocalDateTime to,
                                     Integer pageNum, Integer pageSize) {
        int num = (pageNum == null || pageNum < 1) ? 1 : pageNum;
        int size = (pageSize == null || pageSize < 1) ? 20 : Math.min(pageSize, 200);

        IPage<AuditLog> page = auditLogMapper.selectPage(new Page<>(num, size),
                Wrappers.<AuditLog>lambdaQuery()
                        .eq(module != null && !module.isBlank(), AuditLog::getModule, module)
                        .eq(action != null && !action.isBlank(), AuditLog::getAction, action)
                        .eq(userId != null, AuditLog::getUserId, userId)
                        .eq(bizId != null, AuditLog::getBizId, bizId)
                        .like(keyword != null && !keyword.isBlank(), AuditLog::getUserName, keyword)
                        .ge(from != null, AuditLog::getCreatedAt, from)
                        .le(to != null, AuditLog::getCreatedAt, to)
                        .orderByDesc(AuditLog::getId));
        return PageResult.of(page.getTotal(), num, size, page.getRecords());
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
