package com.hxj.oa.document.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hxj.oa.common.security.LoginUser;
import com.hxj.oa.common.util.DataScopeHelper;
import com.hxj.oa.document.dto.LedgerExportQuery;
import com.hxj.oa.document.entity.Document;
import com.hxj.oa.document.entity.DocumentType;
import com.hxj.oa.document.mapper.DocumentMapper;
import com.hxj.oa.document.mapper.DocumentTypeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 台账导出（CSV）。
 *
 * <p>为什么用 CSV 而不是 xlsx：引 EasyExcel/POI 会往 fat jar 里再塞 10MB+ 依赖，
 * 而台账的消费方（财务归档、Excel 复核）对 CSV 完全够用。UTF-8 BOM 是必须的 ——
 * 没有 BOM 时 Excel 会按 GBK 解码，中文列头全成乱码。
 *
 * <p>行级数据范围与单据列表走同一道 {@link DataScopeHelper}：
 * 导出是**最容易绕开界面把全公司数据捞走**的入口，这里的过滤不能省。
 *
 * <p>时间轴统一用<b>归档时间</b>（{@code document.updated_at}）—— 与台账列表显示的
 * 「归档时间」列、页面上的日期筛选是同一个字段。三处各用各的字段是台账类功能最容易踩的坑：
 * 界面显示一个时间、筛选按第二个、导出又按第三个，对不上账却没人能立刻说清。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerExportService {

    /** 单次导出上限：防止一次点出百万行把堆内存打满 */
    public static final int MAX_ROWS = 5000;

    /** 台账口径：已通过(3) / 已归档(6) */
    private static final List<Integer> LEDGER_STATUSES =
            List.of(DocumentService.STATUS_APPROVED, DocumentService.STATUS_ARCHIVED);

    private static final String[] HEADERS = {
            "单据编号", "申请事项", "单据类型", "申请部门", "申请人", "金额", "提交时间", "归档时间", "状态"
    };

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DocumentMapper documentMapper;
    private final DocumentTypeMapper docTypeMapper;

    /** 导出结果（文件名 + 字节内容） */
    public record CsvFile(String fileName, byte[] content) {
    }

    public CsvFile export(LedgerExportQuery q, LoginUser user) {
        LedgerExportQuery query = q == null ? new LedgerExportQuery() : q;

        var qw = Wrappers.<Document>lambdaQuery()
                .eq(Document::getCompanyId, user.getCompanyId())
                .in(Document::getStatus, LEDGER_STATUSES)
                .like(hasText(query.getApplicant()), Document::getApplicantName, query.getApplicant())
                .eq(hasText(query.getDepartment()), Document::getDeptName, query.getDepartment())
                .like(hasText(query.getDocNo()), Document::getDocNo, query.getDocNo());

        if (query.getDateFrom() != null) {
            qw.ge(Document::getUpdatedAt, query.getDateFrom().atStartOfDay());
        }
        if (query.getDateTo() != null) {
            // 止日含当天：用「次日 0 点」做开区间，避免 23:59:59 之后的记录被漏掉
            qw.lt(Document::getUpdatedAt, query.getDateTo().plusDays(1).atStartOfDay());
        }

        String scopeClause = DataScopeHelper.buildClause("", user);
        if (scopeClause != null && !scopeClause.isBlank()) {
            qw.apply(scopeClause);
        }
        qw.orderByDesc(Document::getUpdatedAt).orderByDesc(Document::getId);
        qw.last("LIMIT " + MAX_ROWS);

        List<Document> rows = documentMapper.selectList(qw);
        Map<Long, String> typeNames = new HashMap<>();

        StringBuilder sb = new StringBuilder();
        // Excel 认 BOM 才不按 GBK 解，中文列头必须靠它
        sb.append('\uFEFF');
        appendRow(sb, HEADERS);
        for (Document d : rows) {
            appendRow(sb, new String[]{
                    d.getDocNo(),
                    d.getTitle(),
                    typeNames.computeIfAbsent(d.getDocTypeId(), id -> {
                        DocumentType dt = docTypeMapper.selectById(id);
                        return dt == null ? "" : dt.getName();
                    }),
                    d.getDeptName(),
                    d.getApplicantName(),
                    d.getAmount() == null ? "" : d.getAmount().toPlainString(),
                    fmt(d.getSubmittedAt()),
                    fmt(d.getUpdatedAt()),
                    statusText(d.getStatus())
            });
        }

        String fileName = "台账档案_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + ".csv";
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        log.info("导出台账 userId={} rows={} bytes={} 条件[applicant={} dept={} docNo={} from={} to={}]",
                user.getUserId(), rows.size(), bytes.length,
                query.getApplicant(), query.getDepartment(), query.getDocNo(),
                query.getDateFrom(), query.getDateTo());
        return new CsvFile(fileName, bytes);
    }

    // ------------------------------------------------------------------ 内部

    private static void appendRow(StringBuilder sb, String[] cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(cells[i]));
        }
        sb.append("\r\n");
    }

    /**
     * CSV 单元格转义。
     *
     * <p>除了标准的「含分隔符/引号/换行就加引号并转义引号」，
     * 还要挡 **公式注入**：单据标题由用户自由填写，若以 {@code = + - @} 开头，
     * Excel 打开后会当公式执行（`=HYPERLINK(...)` 之类可以外带数据）。
     * 做法是前置一个单引号，Excel 会按文本显示且不显示该引号。
     */
    static String escape(String raw) {
        String v = raw == null ? "" : raw;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        boolean needQuote = v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0;
        if (needQuote) {
            v = '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String fmt(LocalDateTime t) {
        return t == null ? "" : t.format(TS);
    }

    private static String statusText(Integer status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case DocumentService.STATUS_APPROVED -> "已通过";
            case DocumentService.STATUS_ARCHIVED -> "已归档";
            default -> String.valueOf(status);
        };
    }

    /** 供测试断言列数一致 */
    public static int columnCount() {
        return HEADERS.length;
    }

    /** 供测试断言表头内容 */
    public static List<String> headerNames() {
        return new ArrayList<>(List.of(HEADERS));
    }
}
