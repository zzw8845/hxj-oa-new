package com.hxj.oa.document.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hxj.oa.common.annotation.Audit;
import com.hxj.oa.common.api.PageResult;
import com.hxj.oa.common.api.R;
import com.hxj.oa.common.security.UserContext;
import com.hxj.oa.document.dto.DocumentCreateRequest;
import com.hxj.oa.document.dto.DocumentDetailVO;
import com.hxj.oa.document.dto.DocumentQuery;
import com.hxj.oa.document.dto.DocumentStatsVO;
import com.hxj.oa.document.entity.Document;
import com.hxj.oa.document.entity.DocumentType;
import com.hxj.oa.document.mapper.DocumentTypeMapper;
import com.hxj.oa.document.dto.LedgerExportQuery;
import com.hxj.oa.document.service.DocumentService;
import com.hxj.oa.document.service.LedgerExportService;
import com.hxj.oa.common.security.RequirePerm;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 单据：发起、提交、查询、撤回、台账导出 */
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;
    private final DocumentTypeMapper docTypeMapper;
    private final LedgerExportService ledgerExportService;

    /** 单据类型清单（前端左侧菜单的核心数据源） */
    @GetMapping("/types")
    public R<List<DocumentType>> types() {
        Long companyId = UserContext.require().getCompanyId();
        return R.ok(docTypeMapper.selectList(Wrappers.<DocumentType>lambdaQuery()
                .eq(DocumentType::getStatus, 1)
                .and(w -> w.eq(DocumentType::getCompanyId, companyId).or().isNull(DocumentType::getCompanyId))
                .orderByAsc(DocumentType::getSortNo)));
    }

    /** 保存草稿 */
    @PostMapping
    @Audit(module = "document", action = "createDraft")
    public R<Document> create(@Valid @RequestBody DocumentCreateRequest req) {
        return R.ok(documentService.createDraft(req, UserContext.require()), "草稿已保存");
    }

    /** 更新草稿 */
    @PutMapping("/{id}")
    @Audit(module = "document", action = "updateDraft")
    public R<Document> update(@PathVariable Long id, @RequestBody DocumentCreateRequest req) {
        return R.ok(documentService.updateDraft(id, req, UserContext.require()), "已保存");
    }

    /** 提交审批 */
    @PostMapping("/{id}/submit")
    @Audit(module = "document", action = "submit")
    public R<Document> submit(@PathVariable Long id) {
        return R.ok(documentService.submit(id, UserContext.require()), "已提交审批");
    }

    /** 撤回 */
    @PostMapping("/{id}/withdraw")
    @Audit(module = "document", action = "withdraw")
    public R<Document> withdraw(@PathVariable Long id) {
        return R.ok(documentService.withdraw(id, UserContext.require()), "已撤回");
    }

    /** 详情（含按当前节点裁剪的表单、流转记录、可执行动作） */
    @GetMapping("/{id}")
    public R<DocumentDetailVO> detail(@PathVariable Long id) {
        return R.ok(documentService.detail(id, UserContext.require()));
    }

    /** 列表（受行级数据范围约束） */
    @GetMapping
    public R<PageResult<Document>> page(DocumentQuery query) {
        return R.ok(documentService.page(query, UserContext.require()));
    }

    /**
     * 按状态统计（看板/首页用），受行级数据范围约束。
     *
     * <p>路径是字面量 {@code /stats}，Spring 会优先于 {@code /{id}} 匹配，
     * 与已有的 {@code /types} 同理，不会把 stats 当成 id 解析。
     */
    @GetMapping("/stats")
    public R<DocumentStatsVO> stats() {
        return R.ok(documentService.stats(UserContext.require()));
    }

    /**
     * 台账导出（CSV）。
     *
     * <p>路径同样是字面量，优先于 {@code /{id}} 匹配。
     *
     * <p>挂 {@code document:export}（ADMIN / GM / 财务总监 / 会计持有）——
     * 导出是唯一「一次点击把整个台账搬走」的入口，必须比「看台账」更严。
     * 导出行为本身也会进审计（detail 里带上了筛选条件与行数）。
     *
     * <p>前端文件名走 RFC 5987 的 {@code filename*}：中文文件名直接塞 {@code filename=}
     * 会被截断或乱码（与附件下载同一处理）。
     */
    @GetMapping("/export")
    @RequirePerm("document:export")
    @Audit(module = "document", action = "exportLedger")
    public ResponseEntity<byte[]> exportLedger(LedgerExportQuery query) {
        LedgerExportService.CsvFile file = ledgerExportService.export(query, UserContext.require());
        String encoded = URLEncoder.encode(file.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                // 导出内容含用户填写的自由文本，明确禁止浏览器嗅探类型
                .header("X-Content-Type-Options", "nosniff")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(file.content());
    }
}
