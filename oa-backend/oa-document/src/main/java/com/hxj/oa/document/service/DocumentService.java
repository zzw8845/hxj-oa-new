package com.hxj.oa.document.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hxj.oa.common.api.PageResult;
import com.hxj.oa.common.exception.BizException;
import com.hxj.oa.common.security.LoginUser;
import com.hxj.oa.common.util.DataScopeHelper;
import com.hxj.oa.common.util.JsonColumn;
import com.hxj.oa.common.util.JsonUtils;
import com.hxj.oa.document.dto.AttachmentVO;
import com.hxj.oa.document.dto.DocumentCreateRequest;
import com.hxj.oa.document.dto.DocumentDetailVO;
import com.hxj.oa.document.dto.DocumentLinkVO;
import com.hxj.oa.document.dto.DocumentQuery;
import com.hxj.oa.document.dto.DocumentStatsVO;
import com.hxj.oa.document.entity.*;
import com.hxj.oa.document.mapper.*;
import com.hxj.oa.flow.entity.FlowConfig;
import com.hxj.oa.flow.entity.FlowInstance;
import com.hxj.oa.flow.entity.FlowInstanceNode;
import com.hxj.oa.flow.dto.FlowStartRequest;
import com.hxj.oa.flow.mapper.FlowConfigMapper;
import com.hxj.oa.flow.mapper.FlowInstanceNodeMapper;
import com.hxj.oa.flow.service.FlowRuntimeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/** 单据服务：创建/提交/撤回/查询，含行级数据范围控制 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    public static final int STATUS_DRAFT = 0;
    public static final int STATUS_WAIT = 1;
    public static final int STATUS_RUNNING = 2;
    public static final int STATUS_APPROVED = 3;
    public static final int STATUS_REJECTED = 4;
    public static final int STATUS_WITHDRAWN = 5;
    public static final int STATUS_ARCHIVED = 6;

    private final DocumentMapper documentMapper;
    private final DocumentTypeMapper docTypeMapper;
    private final DocumentLinkMapper linkMapper;
    private final AttachmentMapper attachmentMapper;
    private final FormTemplateService formTemplateService;
    private final DocNoGenerator docNoGenerator;
    private final FlowRuntimeService flowRuntimeService;
    private final FlowConfigMapper flowConfigMapper;
    private final FlowInstanceNodeMapper flowInstanceNodeMapper;
    private final SealService sealService;

    // ============================================================ 创建 / 更新

    @Transactional(rollbackFor = Exception.class)
    public Document createDraft(DocumentCreateRequest req, LoginUser user) {
        DocumentType docType = docTypeMapper.selectById(req.getDocTypeId());
        if (docType == null || docType.getStatus() == null || docType.getStatus() != 1) {
            throw BizException.notFound("单据类型不存在或已停用: " + req.getDocTypeId());
        }

        FormTemplate tpl = formTemplateService.getEffective(req.getDocTypeId());
        Map<String, Object> formData = req.getFormData() == null ? new HashMap<>() : req.getFormData();

        Document doc = new Document();
        doc.setDocNo(docNoGenerator.next(user.getCompanyId(), docType.getCategory()));
        doc.setCompanyId(user.getCompanyId());
        doc.setDocTypeId(docType.getId());
        doc.setBusinessCategory(docType.getCategory());
        doc.setTitle(resolveTitle(req, formData));
        doc.setApplicantId(user.getUserId());
        doc.setApplicantName(user.getRealName());
        doc.setDeptId(user.getDeptId());
        doc.setDeptName(user.getDeptName());
        doc.setAmount(resolveAmount(req, formData));
        doc.setReason(req.getReason() != null ? req.getReason() : str(formData.get("reason")));
        doc.setInvoiceSummary(req.getInvoiceSummary());
        doc.setFormData(JsonUtils.toJson(formData));
        doc.setFormTemplateId(tpl.getId());
        doc.setFormTemplateVer(tpl.getVersion());
        doc.setNeedPostMaterial(req.getNeedPostMaterial() == null ? 0 : req.getNeedPostMaterial());
        doc.setPostMaterialStatus(0);
        doc.setStatus(STATUS_DRAFT);
        doc.setPriority(req.getPriority() == null ? 0 : req.getPriority());
        doc.setCreatedBy(user.getUserId());
        doc.setUpdatedBy(user.getUserId());
        documentMapper.insert(doc);

        saveLinks(doc.getId(), req.getLinkedDocIds());
        log.info("创建单据草稿 docNo={} type={} by={}", doc.getDocNo(), docType.getName(), user.getRealName());
        return doc;
    }

    @Transactional(rollbackFor = Exception.class)
    public Document updateDraft(Long id, DocumentCreateRequest req, LoginUser user) {
        Document doc = requireOwnEditable(id, user);
        Map<String, Object> formData = req.getFormData() == null ? new HashMap<>() : req.getFormData();

        doc.setTitle(resolveTitle(req, formData));
        doc.setAmount(resolveAmount(req, formData));
        doc.setReason(req.getReason() != null ? req.getReason() : str(formData.get("reason")));
        doc.setFormData(JsonUtils.toJson(formData));
        doc.setUpdatedBy(user.getUserId());
        documentMapper.updateById(doc);

        linkMapper.delete(Wrappers.<DocumentLink>lambdaQuery().eq(DocumentLink::getDocumentId, id));
        saveLinks(id, req.getLinkedDocIds());
        return doc;
    }

    /** 提交：服务端二次校验 → 启动流程 → 回写当前节点 */
    @Transactional(rollbackFor = Exception.class)
    public Document submit(Long id, LoginUser user) {
        Document doc = requireOwnEditable(id, user);
        if (doc.getFormTemplateId() == null) {
            throw new BizException("单据缺少表单模板，无法提交");
        }

        Map<String, Object> formData = JsonColumn.toMap(doc.getFormData());
        List<Map<String, String>> errors = formTemplateService.validate(doc.getFormTemplateId(), formData);
        if (!errors.isEmpty()) {
            throw new BizException("表单校验未通过：" + JsonUtils.toJson(errors));
        }

        DocumentType docType = docTypeMapper.selectById(doc.getDocTypeId());
        Long flowConfigId = docType == null ? null : docType.getFlowConfigId();
        if (flowConfigId == null) {
            throw BizException.of("单据类型「%s」未配置审批流程", docType == null ? "-" : docType.getName());
        }
        FlowConfig flowConfig = flowConfigMapper.selectById(flowConfigId);
        if (flowConfig == null) {
            throw BizException.notFound("流程配置不存在: " + flowConfigId);
        }
        if (flowConfig.getProcDefKey() == null) {
            throw BizException.of("流程「%s」尚未部署到引擎，请先在流程配置中部署", flowConfig.getName());
        }

        doc.setStatus(STATUS_WAIT);
        doc.setSubmittedAt(LocalDateTime.now());
        doc.setUpdatedBy(user.getUserId());
        documentMapper.updateById(doc);

        FlowInstance inst = flowRuntimeService.start(flowConfig, FlowStartRequest.builder()
                .documentId(doc.getId())
                .docNo(doc.getDocNo())
                .companyId(doc.getCompanyId())
                .applicantId(doc.getApplicantId())
                .applicantDeptId(doc.getDeptId())
                .bizCategory(doc.getBusinessCategory())
                .docTypeId(doc.getDocTypeId())
                .amount(doc.getAmount())
                .formData(formData)
                .build());

        doc.setFlowInstanceId(inst.getId());
        doc.setCurrentNodeKey(inst.getCurrentNodeKey());
        doc.setCurrentNodeName(nodeName(doc.getDocTypeId(), inst.getCurrentNodeKey()));
        doc.setStatus(STATUS_RUNNING);
        documentMapper.updateById(doc);

        // 用印类单据：提交即产生「待用印」台账记录。
        // 不做这一步，印章管理岗在台账里就看不到任何待办 —— 因为台账行原本只能靠"登记用印"创建，
        // 而登记用印又必须在台账里点，形成死循环。
        sealService.ensureApplyOnSubmit(doc);

        log.info("单据提交 docNo={} flow={} 当前节点={}", doc.getDocNo(), flowConfig.getName(), doc.getCurrentNodeKey());
        return doc;
    }

    @Transactional(rollbackFor = Exception.class)
    public Document withdraw(Long id, LoginUser user) {
        Document doc = documentMapper.selectById(id);
        if (doc == null) {
            throw BizException.notFound("单据不存在");
        }
        if (!Objects.equals(doc.getApplicantId(), user.getUserId())) {
            throw BizException.forbidden("只能撤回本人发起的单据");
        }
        if (doc.getStatus() == null || (doc.getStatus() != STATUS_WAIT && doc.getStatus() != STATUS_RUNNING)) {
            throw new BizException("当前状态不允许撤回");
        }
        FlowInstance inst = flowRuntimeService.byDocument(id);
        flowRuntimeService.withdraw(inst, "发起人撤回");

        doc.setStatus(STATUS_WITHDRAWN);
        doc.setUpdatedBy(user.getUserId());
        documentMapper.updateById(doc);
        // updateById 默认忽略 null 字段，置空当前节点必须走显式 set()
        documentMapper.update(null, Wrappers.<Document>lambdaUpdate()
                .eq(Document::getId, id)
                .set(Document::getCurrentNodeKey, null)
                .set(Document::getCurrentNodeName, null));
        return doc;
    }

    // ============================================================ 查询

    public PageResult<Document> page(DocumentQuery q, LoginUser user) {
        QueryWrapper<Document> qw = new QueryWrapper<>();
        qw.eq("company_id", user.getCompanyId());
        if (q.getDocTypeId() != null) {
            qw.eq("doc_type_id", q.getDocTypeId());
        }
        if (q.getBusinessCategory() != null && !q.getBusinessCategory().isBlank()) {
            qw.eq("business_category", q.getBusinessCategory());
        }
        if (q.getStatus() != null) {
            qw.eq("status", q.getStatus());
        }
        if (q.getStatusList() != null && !q.getStatusList().isEmpty()) {
            // 多值状态：台账(3,6)、快捷视图(1,2 / 4 / 3,6)。全部下推后前端才能真分页
            qw.in("status", q.getStatusList());
        }
        if (q.getApplicant() != null && !q.getApplicant().isBlank()) {
            qw.like("applicant_name", q.getApplicant().trim());
        }
        if (q.getDepartment() != null && !q.getDepartment().isBlank()) {
            // 页面下拉给的是部门名，精确等值
            qw.eq("dept_name", q.getDepartment().trim());
        }
        if (q.getDocNo() != null && !q.getDocNo().isBlank()) {
            // 台账"单据编号"必须只匹配 doc_no —— 走 keyword 会连标题/申请人一起命中
            qw.like("doc_no", q.getDocNo().trim());
        }
        if (q.getUpdatedAtFrom() != null) {
            qw.ge("updated_at", q.getUpdatedAtFrom());
        }
        if (q.getUpdatedAtTo() != null) {
            qw.le("updated_at", q.getUpdatedAtTo());
        }
        if (q.getKeyword() != null && !q.getKeyword().isBlank()) {
            // 必须覆盖 applicant_name：界面的搜索框写的是「搜索单号、申请人或事项」，
            // 只匹配 title/doc_no 的话用户按人名搜会得到"空结果"，而界面不会提示
            // "其实我没搜申请人" —— 这会让人以为单据不存在。
            String kw = q.getKeyword().trim();
            qw.and(w -> w.like("title", kw)
                    .or().like("doc_no", kw)
                    .or().like("applicant_name", kw));
        }
        if (q.getAmountFrom() != null) {
            qw.ge("amount", q.getAmountFrom());
        }
        if (q.getAmountTo() != null) {
            qw.le("amount", q.getAmountTo());
        }

        // 「我发起的」不受数据范围影响（本来就只看自己）
        if ("mine".equalsIgnoreCase(q.getScope())) {
            qw.eq("applicant_id", user.getUserId());
        } else {
            // 关键：行级数据范围，唯一防线（MySQL 无 RLS 兜底，见报告 7.4）
            String scopeClause = DataScopeHelper.buildClause("", user);
            if (scopeClause != null && !scopeClause.isBlank()) {
                qw.apply(scopeClause);
            }
        }
        qw.orderByDesc("created_at");

        int pageNum = q.getPageNum() == null || q.getPageNum() < 1 ? 1 : q.getPageNum();
        int pageSize = q.getPageSize() == null || q.getPageSize() < 1 ? 20 : Math.min(q.getPageSize(), 200);
        IPage<Document> page = documentMapper.selectPage(new Page<>(pageNum, pageSize), qw);
        return PageResult.of(page.getTotal(), pageNum, pageSize, page.getRecords());
    }

    /**
     * 单据统计：按状态聚合，受行级数据范围约束。
     *
     * <p>刻意走 {@link DataScopeHelper} 同一道范围判定，而不是像
     * {@code DocumentMapper.countByStatus} 那样只按 company_id 聚合
     * ——否则普通员工（SELF 范围）会在看板上看到全公司的数字，
     * 与他在列表里能看到的单据对不上，等于把行级权限从统计口径绕过去了。
     *
     * <p>一次 group by 拿到全部分组，避免 6 次 count 往返。
     */
    public DocumentStatsVO stats(LoginUser user) {
        QueryWrapper<Document> qw = new QueryWrapper<>();
        qw.select("status", "COUNT(*) AS cnt");
        qw.eq("company_id", user.getCompanyId());
        String scopeClause = DataScopeHelper.buildClause("", user);
        if (scopeClause != null && !scopeClause.isBlank()) {
            qw.apply(scopeClause);
        }
        qw.groupBy("status");

        DocumentStatsVO vo = new DocumentStatsVO();
        for (Map<String, Object> row : documentMapper.selectMaps(qw)) {
            Integer status = row.get("status") == null ? null : ((Number) row.get("status")).intValue();
            if (status == null) {
                continue;
            }
            long cnt = row.get("cnt") == null ? 0L : ((Number) row.get("cnt")).longValue();
            vo.setTotal(vo.getTotal() + cnt);
            switch (status) {
                case STATUS_DRAFT -> vo.setDraft(vo.getDraft() + cnt);
                case STATUS_WAIT, STATUS_RUNNING -> vo.setRunning(vo.getRunning() + cnt);
                case STATUS_APPROVED, STATUS_ARCHIVED -> vo.setApproved(vo.getApproved() + cnt);
                case STATUS_REJECTED -> vo.setRejected(vo.getRejected() + cnt);
                case STATUS_WITHDRAWN -> vo.setWithdrawn(vo.getWithdrawn() + cnt);
                default -> {
                    // 未知状态不计入任何分类，但仍计入 total，避免「分项之和 ≠ 总数」时无从解释
                }
            }
        }

        // 近 7 天每日发起量：必须复用上面同一道行级范围，否则柱状图与圆环口径不一致，
        // 同一屏上两个卡片各说各话（一个按范围、一个按全公司）比没有数字更糟。
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(6);
        QueryWrapper<Document> dq = new QueryWrapper<>();
        dq.select("DATE(submitted_at) AS d", "COUNT(*) AS cnt");
        dq.eq("company_id", user.getCompanyId());
        if (scopeClause != null && !scopeClause.isBlank()) {
            dq.apply(scopeClause);
        }
        dq.isNotNull("submitted_at");
        dq.ge("submitted_at", from.atStartOfDay());
        dq.groupBy("DATE(submitted_at)");

        Map<String, Long> byDate = new HashMap<>();
        for (Map<String, Object> row : documentMapper.selectMaps(dq)) {
            Object d = row.get("d");
            if (d == null) {
                continue;
            }
            // DATE() 在不同驱动下可能返回 java.sql.Date 或 String，统一截前 10 位
            byDate.put(String.valueOf(d).substring(0, 10),
                    row.get("cnt") == null ? 0L : ((Number) row.get("cnt")).longValue());
        }
        long week = 0L;
        List<DocumentStatsVO.DailyCount> daily = new ArrayList<>(7);
        for (int i = 6; i >= 0; i--) {
            String key = today.minusDays(i).toString();
            long c = byDate.getOrDefault(key, 0L);
            daily.add(new DocumentStatsVO.DailyCount(key, c));
            week += c;
        }
        vo.setDailyCounts(daily);
        vo.setWeekTotal(week);
        return vo;
    }

    /** 单据详情：按「当前用户在此单据上的视角节点」裁剪字段权限 */
    public DocumentDetailVO detail(Long id, LoginUser user) {        Document doc = documentMapper.selectById(id);
        if (doc == null) {
            throw BizException.notFound("单据不存在");
        }
        assertVisible(doc, user);

        DocumentType docType = docTypeMapper.selectById(doc.getDocTypeId());
        FlowConfig flowConfig = (docType != null && docType.getFlowConfigId() != null)
                ? flowConfigMapper.selectById(docType.getFlowConfigId()) : null;

        // 找出「当前用户在这个单据上正在处理的任务」→ 决定字段可编辑范围
        List<FlowInstanceNode> history = flowInstanceNodeMapper.selectHistoryByDocument(id);
        String pendingTaskId = null;
        for (FlowInstanceNode n : history) {
            if (n.getStatus() != null && (n.getStatus() == 0 || n.getStatus() == 1)
                    && n.getAssigneeId() != null && n.getAssigneeId().equals(user.getUserId())) {
                pendingTaskId = n.getTaskId();
                break;
            }
        }
        String viewingNode = pendingTaskId != null ? nodeKeyOf(history, pendingTaskId) : "n1";

        DocumentDetailVO vo = new DocumentDetailVO();
        vo.setDocument(doc);
        vo.setDocTypeName(docType == null ? null : docType.getName());
        vo.setFlowName(flowConfig == null ? null : flowConfig.getName());
        vo.setFlowVersion(doc.getFormTemplateVer());
        vo.setViewingNodeKey(viewingNode);
        vo.setFlowHistory(history);
        vo.setPendingTaskId(pendingTaskId);
        vo.setRequireAttachment(pendingTaskId != null && nodeRequiresAttachment(doc, viewingNode));
        vo.setAvailableActions(availableActions(doc, pendingTaskId, user));
        if (doc.getFormTemplateId() != null) {
            vo.setFormSchema(formTemplateService.renderSchema(doc.getFormTemplateId(), viewingNode));
        }
        vo.setAttachments(attachmentMapper.selectList(Wrappers.<Attachment>lambdaQuery()
                        .eq(Attachment::getDocumentId, id).orderByAsc(Attachment::getId))
                .stream().map(AttachmentVO::of).toList());
        List<DocumentLink> linkRows = linkMapper.selectList(Wrappers.<DocumentLink>lambdaQuery()
                .eq(DocumentLink::getDocumentId, id).orderByAsc(DocumentLink::getId));
        if (linkRows.isEmpty()) {
            vo.setLinks(List.of());
        } else {
            // 一次批量捞回被关联单据，避免按 id 逐个回查（N+1）
            List<Long> linkedIds = linkRows.stream().map(DocumentLink::getLinkedId)
                    .filter(Objects::nonNull).distinct().toList();
            Map<Long, Document> linkedDocs = linkedIds.isEmpty() ? Map.of()
                    : documentMapper.selectBatchIds(linkedIds).stream()
                    .collect(Collectors.toMap(Document::getId, d -> d));
            vo.setLinks(linkRows.stream().map(l -> {
                Document ld = linkedDocs.get(l.getLinkedId());
                // ld 为空说明被关联的单据已被删除（selectBatchIds 带逻辑删除条件）——
                // 此时编号/标题留空，前端据此提示「关联单据已不存在」，而不是装作没事
                return DocumentLinkVO.of(l,
                        ld == null ? null : ld.getDocNo(),
                        ld == null ? null : ld.getTitle(),
                        ld == null ? null : ld.getStatus());
            }).toList());
        }
        return vo;
    }

    private List<String> availableActions(Document doc, String pendingTaskId, LoginUser user) {
        List<String> actions = new ArrayList<>();
        boolean isApplicant = Objects.equals(doc.getApplicantId(), user.getUserId());
        if (pendingTaskId != null) {
            actions.add("approve");
            actions.add("reject");
            actions.add("countersign");
            actions.add("supplement");
        }
        if (isApplicant && doc.getStatus() != null
                && (doc.getStatus() == STATUS_WAIT || doc.getStatus() == STATUS_RUNNING)) {
            actions.add("withdraw");
        }
        if (isApplicant && doc.getStatus() != null
                && (doc.getStatus() == STATUS_DRAFT || doc.getStatus() == STATUS_REJECTED
                || doc.getStatus() == STATUS_WITHDRAWN)) {
            actions.add("edit");
            actions.add("resubmit");
        }
        return actions;
    }

    private String nodeKeyOf(List<FlowInstanceNode> history, String taskId) {
        return history.stream()
                .filter(n -> taskId.equals(n.getTaskId()))
                .map(FlowInstanceNode::getNodeKey)
                .findFirst().orElse("n1");
    }

    // ============================================================ 内部

    private Document requireOwnEditable(Long id, LoginUser user) {
        Document doc = documentMapper.selectById(id);
        if (doc == null) {
            throw BizException.notFound("单据不存在");
        }
        if (!Objects.equals(doc.getApplicantId(), user.getUserId())) {
            throw BizException.forbidden("只能操作本人发起的单据");
        }
        if (doc.getStatus() != null && doc.getStatus() != STATUS_DRAFT
                && doc.getStatus() != STATUS_REJECTED && doc.getStatus() != STATUS_WITHDRAWN) {
            throw new BizException("当前单据状态不允许修改或提交");
        }
        return doc;
    }

    /**
     * 详情可见性：数据范围 或 该用户是流程参与者。
     *
     * <p>public 是刻意为之：附件下载、附件删除等一切「挂在单据上的东西」都必须过同一道可见性判定，
     * 各自再实现一份必然会漂移。
     *
     * <p>【历史缺陷 · 横向越权】本方法曾用一段独立的 Java switch 复刻数据范围，DEPT/CENTER/CUSTOM_DEPT
     * 分支写成 {@code Objects.equals(doc.getDeptId(), user.getDeptId())
     * || (user.getDeptPath() != null && user.getDeptPath().length() > 1)} —— 后半段**只和用户有关、
     * 与单据无关**，只要用户有部门（path 形如 {@code /6/}，长度恒 &gt; 1）就恒为 true。
     * 后果：任何 dept/center 范围的用户都能按 ID 读到**任意**单据的详情、附件列表并下载附件，
     * 哪怕这些单据在他的列表里根本不存在（典型 IDOR）。根因是「列表走 SQL 片段、详情走 Java 分支」
     * 两套实现并存，必然漂移。
     *
     * <p>现已收敛为与列表/统计/台账**同源**：直接把
     * {@link DataScopeHelper#buildClause} 生成的片段套回本单据自身，
     * 让「详情可见 ⟺ 出现在我的列表里」成为构造性事实，而不是靠人工同步两处代码。
     */
    public void assertVisible(Document doc, LoginUser user) {
        if (user.hasRole("ADMIN")) {
            return;
        }
        if (inDataScope(doc, user)) {
            return;
        }
        // 流程参与者（审批人/被抄送人）也应可见：待办/已办里的单据即使不在自己的数据范围内，
        // 也必须能打开，否则审批人拿到待办却点不进去。
        boolean participant = flowInstanceNodeMapper.selectHistoryByDocument(doc.getId()).stream()
                .anyMatch(n -> Objects.equals(n.getAssigneeId(), user.getUserId())
                        || (n.getCandidateIds() != null && n.getCandidateIds().contains(String.valueOf(user.getUserId()))));
        if (!participant) {
            throw BizException.forbidden("无权查看该单据");
        }
    }

    /**
     * 行级数据范围判定：与列表查询共用同一个 SQL 片段生成器。
     *
     * <p>做法是把片段原样套回**本单据自己**做一次主键 count —— 命中即说明这张单据
     * 落在该用户的数据范围内。代价是一次走主键的 count（毫秒级），换到的是
     * 「详情口径不可能与列表口径漂移」这一结构性保证。
     *
     * <p>{@code buildClause} 返回 null 表示「无任何行级限制」（仅当用户没有公司归属、
     * 且范围是最宽的 COMPANY 时出现）；此时与列表「不加过滤」保持一致，视为可见。
     */
    private boolean inDataScope(Document doc, LoginUser user) {
        String clause = DataScopeHelper.buildClause("", user);
        if (clause == null || clause.isBlank()) {
            return true;
        }
        QueryWrapper<Document> qw = new QueryWrapper<>();
        qw.eq("id", doc.getId());
        qw.apply(clause);
        Long matched = documentMapper.selectCount(qw);
        return matched != null && matched > 0;
    }

    private void saveLinks(Long documentId, List<Long> linkedDocIds) {
        if (linkedDocIds == null || linkedDocIds.isEmpty()) {
            return;
        }
        for (Long linked : linkedDocIds) {
            DocumentLink link = new DocumentLink();
            link.setDocumentId(documentId);
            link.setLinkedId(linked);
            link.setLinkType("prev_doc");
            linkMapper.insert(link);
        }
    }

    private String resolveTitle(DocumentCreateRequest req, Map<String, Object> formData) {
        if (req.getTitle() != null && !req.getTitle().isBlank()) {
            return req.getTitle();
        }
        for (String key : List.of("title", "sealProject", "reason")) {
            String v = str(formData.get(key));
            if (v != null && !v.isBlank()) {
                return v.length() > 128 ? v.substring(0, 128) : v;
            }
        }
        return "未命名单据";
    }

    private BigDecimal resolveAmount(DocumentCreateRequest req, Map<String, Object> formData) {
        if (req.getAmount() != null) {
            return req.getAmount();
        }
        Object v = formData.get("amount");
        if (v == null) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(String.valueOf(v));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** 该节点是否配置了「必须上传办理凭证」 */
    private boolean nodeRequiresAttachment(Document doc, String nodeKey) {
        if (nodeKey == null) {
            return false;
        }
        DocumentType dt = docTypeMapper.selectById(doc.getDocTypeId());
        if (dt == null || dt.getFlowConfigId() == null) {
            return false;
        }
        return flowRuntimeService.nodesOf(dt.getFlowConfigId()).stream()
                .filter(n -> nodeKey.equals(n.getNodeKey()))
                .anyMatch(n -> n.getRequireAttachment() != null && n.getRequireAttachment() == 1);
    }

    private String nodeName(Long docTypeId, String nodeKey) {
        if (nodeKey == null) {
            return null;
        }
        DocumentType dt = docTypeMapper.selectById(docTypeId);
        if (dt == null || dt.getFlowConfigId() == null) {
            return nodeKey;
        }
        return flowRuntimeService.nodesOf(dt.getFlowConfigId()).stream()
                .filter(n -> nodeKey.equals(n.getNodeKey()))
                .map(n -> n.getNodeName())
                .findFirst().orElse(nodeKey);
    }
}
