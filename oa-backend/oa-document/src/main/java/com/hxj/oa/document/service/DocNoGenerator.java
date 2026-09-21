package com.hxj.oa.document.service;

import com.hxj.oa.document.mapper.CodeSequenceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 单据编号生成：{前缀}{日期}{4位序号}，如 FK202609180001。
 *
 * 防重号三层保险：
 * 1) code_sequence 上 (company_id, biz_prefix, period) 唯一索引
 * 2) SELECT ... FOR UPDATE 行锁
 * 3) document.doc_no 唯一索引兜底
 *
 * 取号用独立短事务（REQUIRES_NEW），尽快释放行锁；代价是外层回滚会跳号，
 * 编号跳号在 OA 场景是可接受的（不重号才是硬要求）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocNoGenerator {

    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final Map<String, String> PREFIX = Map.of(
            "DAILY", "FK",
            "BIZ", "FK",
            "REIMBURSE", "BX",
            "SEAL", "YY"
    );

    private final CodeSequenceMapper sequenceMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public String next(Long companyId, String bizCategory) {
        String prefix = PREFIX.getOrDefault(bizCategory, "OA");
        String period = LocalDate.now().format(PERIOD);

        sequenceMapper.insertIfAbsent(companyId, prefix, period);
        Long current = sequenceMapper.selectForUpdate(companyId, prefix, period);
        sequenceMapper.increment(companyId, prefix, period);

        long next = (current == null ? 0L : current) + 1L;
        String docNo = prefix + period + String.format("%04d", next);
        log.debug("生成单据编号 companyId={} category={} docNo={}", companyId, bizCategory, docNo);
        return docNo;
    }
}
