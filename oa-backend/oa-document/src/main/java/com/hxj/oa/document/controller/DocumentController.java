package com.hxj.oa.document.controller;

import com.hxj.oa.common.annotation.Audit;
import com.hxj.oa.common.api.PageResult;
import com.hxj.oa.common.api.R;
import com.hxj.oa.common.security.UserContext;
import com.hxj.oa.document.dto.DocumentCreateRequest;
import com.hxj.oa.document.dto.DocumentDetailVO;
import com.hxj.oa.document.dto.DocumentQuery;
import com.hxj.oa.document.dto.DocumentStatsVO;
import com.hxj.oa.document.entity.Document;
import com.hxj.oa.document.dto.DocumentTypeVO;
import com.hxj.oa.document.dto.LedgerExportQuery;
import com.hxj.oa.document.service.DocumentService;
import com.hxj.oa.document.service.DocumentTypeAdminService;
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

/**
 * 单据：发起、提交、查询、撤回、台账导出。
 *
 * <p><b>门控现状（2026-09-28 补，C4/D8 出口 B 落地后）</b>：
 * 读路径 —— 详情 / 列表 / 统计用 {@code document:view:*}（OR），导出用 {@code document:export}；
 * 写路径 —— <b>发起三件套（创建 / 改草稿 / 提交）挂 {@code document:create}</b>；
 * 撤回不走权限点 —— 它拦的是「是不是本人发起的」，属行级归属校验，
 * 用权限点表达反而会把它降级成角色判断。
 *
 * <p><b>为什么建单的门控拖到今天才补</b>：它是<b>真收权</b> —— 业务审批角色
 * （GM / 财务总监 / 会计 / 出纳 / 内控）都不持有 {@code document:create}，补上去他们当场
 * 发不了单。所以它必须与「谁能发起单据」这个产品决策一起定，不能顺手补。
 * <b>该决策已于 2026-09-28 由用户拍板（C4/D8 出口 B）</b>：发单是业务动作，只归持单角色
 * （DEPT_HEAD / EMPLOYEE），管理员是纯管理账号、不参与业务 —— 本门控即该决策的执行。
 * {@code /types}（单据类型清单）保持不挂：它同时服务于列表的类型名渲染，
 * 挂上会把"看单据的人"一并拦掉，且它是只读、无越权面。
 */
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;
    private final DocumentTypeAdminService docTypeAdminService;
    private final LedgerExportService ledgerExportService;

    /**
     * 单据类型清单（前端左侧菜单的核心数据源）。
     *
     * <p>返回 {@link DocumentTypeVO}：比实体多一个后端下发的 {@code categoryLabel}，
     * 前端业务类型标签一律以此为准，不再维护「DAILY→日常付款」这类写死映射。
     */
    @GetMapping("/types")
    public R<List<DocumentTypeVO>> types() {
        Long companyId = UserContext.require().getCompanyId();
        return R.ok(docTypeAdminService.listEnabled(companyId));
    }

    /**
     * 保存草稿。
     *
     * <p>2026-09-28 补门控：此前 {@code document:create} 是"有声明无引用"的空转控制，
     * 任何能登录的人都能发单。当年不补是因为它与「谁能发起单据」这个产品决策绑在一起；
     * 该决策已定（C4/D8 出口 B）：发单是业务动作，只归持单角色（DEPT_HEAD / EMPLOYEE），
     * 管理员是纯管理账号、不参与业务 —— 现在补上，正是执行这个决策。
     */
    @PostMapping
    @RequirePerm("document:create")
    @Audit(module = "document", action = "createDraft")
    public R<Document> create(@Valid @RequestBody DocumentCreateRequest req) {
        return R.ok(documentService.createDraft(req, UserContext.require()), "草稿已保存");
    }

    /** 更新草稿。与创建同权：改的是自己没提交的草稿，能发起的人才谈得上改 */
    @PutMapping("/{id}")
    @RequirePerm("document:create")
    @Audit(module = "document", action = "updateDraft")
    public R<Document> update(@PathVariable Long id, @RequestBody DocumentCreateRequest req) {
        return R.ok(documentService.updateDraft(id, req, UserContext.require()), "已保存");
    }

    /** 提交审批。与创建同权：提交是"发起"这件事的后半步 */
    @PostMapping("/{id}/submit")
    @RequirePerm("document:create")
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

    /**
     * 详情（含按当前节点裁剪的表单、流转记录、可执行动作）。
     *
     * <p>门控取「单据可见粒度」三点之<b>任一</b>（OR）：{@code document:view:self} /
     * {@code :dept} / {@code :company}。
     *
     * <p>⚠ 这三点<b>不决定「看多宽」</b> —— 能看到哪些单据由行级数据范围决定
     * （{@code DocumentService#assertVisible}，与列表查询共用同一个 SQL 片段生成器）。
     * 权限点只回答「这个人是否属于单据体系」。两者必须分开：若把可见性也做成权限点判断，
     * 就会出现第二份行级权限实现（本项目行级权限只准一份实现）。
     *
     * @param id 单据 ID
     */
    @GetMapping("/{id}")
    @RequirePerm(value = {"document:view:self", "document:view:dept", "document:view:company"},
            logic = RequirePerm.Logic.OR)
    public R<DocumentDetailVO> detail(@PathVariable Long id) {
        return R.ok(documentService.detail(id, UserContext.require()));
    }

    /**
     * 列表（受行级数据范围约束）。
     *
     * <p>筛选条件走 query string 平铺（不是 JSON body），字段见 {@code DocumentQuery}。
     *
     * <p>门控与详情同源：{@code document:view:self} / {@code :dept} / {@code :company} 任一即可（OR）。
     * 为什么用这三点而不是新造一个 {@code document:view}：现网 9 个角色<b>各自至少持有其中一个</b>
     * ⇒ 挂门控是<b>零收权</b>（补门控前必须先做这一步核对，否则不是关门而是悄悄收走一批人的权限）；
     * 新造点则要动种子、8 个角色的绑定与可达性棘轮白名单，属另一件事。
     *
     * @param query 筛选与分页条件
     */
    @GetMapping
    @RequirePerm(value = {"document:view:self", "document:view:dept", "document:view:company"},
            logic = RequirePerm.Logic.OR)
    public R<PageResult<Document>> page(DocumentQuery query) {
        return R.ok(documentService.page(query, UserContext.require()));
    }

    /**
     * 按状态统计（看板/首页用），受行级数据范围约束。
     *
     * <p>路径是字面量 {@code /stats}，Spring 会优先于 {@code /{id}} 匹配，
     * 与已有的 {@code /types} 同理，不会把 stats 当成 id 解析。
     *
     * <p>门控与列表同源（{@code document:view:*} 任一）。统计口径必须与列表一致，
     * 否则「看板数字与台账条数对不上」会变成最难排查的一类问题。
     */
    @GetMapping("/stats")
    @RequirePerm(value = {"document:view:self", "document:view:dept", "document:view:company"},
            logic = RequirePerm.Logic.OR)
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
     *
     * @param query 导出筛选条件（字段见 {@code LedgerExportQuery}）
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
