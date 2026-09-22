package com.hxj.oa.document.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hxj.oa.common.api.PageResult;
import com.hxj.oa.common.exception.BizException;
import com.hxj.oa.common.security.UserContext;
import com.hxj.oa.common.util.JsonColumn;
import com.hxj.oa.document.dto.SealActionReq;
import com.hxj.oa.document.dto.SealLedgerVO;
import com.hxj.oa.document.dto.SealQuery;
import com.hxj.oa.document.entity.Document;
import com.hxj.oa.document.entity.SealApply;
import com.hxj.oa.document.entity.SealRecord;
import com.hxj.oa.document.mapper.DocumentMapper;
import com.hxj.oa.document.mapper.SealApplyMapper;
import com.hxj.oa.document.mapper.SealRecordMapper;
import com.hxj.oa.system.entity.SysDict;
import com.hxj.oa.system.mapper.SysDictMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用印台账与归还闭环。
 *
 * <p>此前 `seal_apply` / `seal_record` 两张表、实体、Mapper 都已建好，
 * 但**没有任何 service / controller / 接口**，两张表 0 行数据 ——
 * 原型 P3 承诺的「用印申请专项 / 印章台账 / 归还闭环」只有壳。
 * 本类补上这条链路。
 *
 * <h3>闭环的状态机（单向，不可跳步）</h3>
 * <pre>
 *   0 待用印 --登记用印--> 1 已用印 --归还--> 2 已归还
 * </pre>
 * 每一步都写一条 {@code seal_record}（谁、何时、做了什么、备注）——「可查」的价值就在这里。
 * 状态只能前进：重复登记用印、没登记就归还、重复归还，都会得到**明确的业务提示**而不是 500，
 * 因为这些都是操作人很容易遇到的正常情形（比如两个管理岗同时看到同一张单）。
 *
 * <h3>三个写进代码的判断</h3>
 * <ol>
 *   <li><b>只有 SEAL 类业务单据能登记用印</b>：否则一张付款单也能被写出一条用印记录，
 *       而台账是给审计看的，错一条就是一整类数据的可信度问题。</li>
 *   <li><b>草稿 / 已驳回 / 已撤回的单据不允许用印</b>：用印必须发生在流程中或流程通过之后，
 *       否则等于"没走审批就盖了章"。已归档（6）允许 —— 归还动作常常发生在归档之后。</li>
 *   <li><b>状态流转用「带条件的 UPDATE」而不是"先查再改"</b>：
 *       {@code UPDATE ... WHERE return_status = 期望值}，影响行数为 0 就说明期间被别人改过。
 *       先查再改在并发下会让两个请求都成功，台账里就出现两条 use。</li>
 * </ol>
 *
 * <h3>台账的可见范围</h3>
 * 按**公司**隔离（通过关联单据的 company_id），刻意**不**套用单据的行级数据范围：
 * 印章管理岗的职责就是看全公司的用印与归还情况，按部门缩小范围反而让他干不了活。
 * 准入由 {@code document:approve:seal} 权限点把住。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SealService {

    /** 归还状态 */
    public static final int ST_WAIT_USE = 0;
    public static final int ST_USED = 1;
    public static final int ST_RETURNED = 2;

    /** 台账动作 */
    public static final String ACTION_USE = "use";
    public static final String ACTION_RETURN = "return";

    /** 用印业务类别（document.business_category） */
    private static final String BIZ_SEAL = "SEAL";
    private static final String DICT_SEAL_TYPE = "seal_type";
    private static final int MAX_PAGE_SIZE = 200;

    /** 允许用印的单据状态：待审批/审批中/已通过/已归档 */
    private static final Set<Integer> SEALABLE_STATUS = Set.of(
            DocumentService.STATUS_WAIT, DocumentService.STATUS_RUNNING,
            DocumentService.STATUS_APPROVED, DocumentService.STATUS_ARCHIVED);

    private final SealApplyMapper applyMapper;
    private final SealRecordMapper recordMapper;
    private final DocumentMapper documentMapper;
    private final SysDictMapper dictMapper;

    /* ================================================================== 查询 */

    public PageResult<SealLedgerVO> page(SealQuery q) {
        Long companyId = UserContext.require().getCompanyId();
        LambdaQueryWrapper<SealApply> w = Wrappers.<SealApply>lambdaQuery();
        if (StringUtils.hasText(q.getSealType())) {
            w.eq(SealApply::getSealType, q.getSealType().trim());
        }
        if (q.getReturnStatus() != null) {
            w.eq(SealApply::getReturnStatus, q.getReturnStatus());
        }
        if (StringUtils.hasText(q.getKeyword())) {
            w.like(SealApply::getSealProject, q.getKeyword().trim());
        }
        LocalDate from = parseDate(q.getDateFrom(), "起始日期");
        LocalDate to = parseDate(q.getDateTo(), "结束日期");
        if (from != null) {
            w.ge(SealApply::getCreatedAt, from.atStartOfDay());
        }
        if (to != null) {
            w.le(SealApply::getCreatedAt, to.atTime(23, 59, 59));
        }
        // 公司隔离：seal_apply 没有 company_id 列，只能通过关联单据过滤。
        // 这里用 inSql 拼的是**数字字面量**（companyId 来自登录态、类型是 Long），
        // 不存在注入面；换成字符串拼接就危险了，所以别改这里的写法。
        w.inSql(SealApply::getDocumentId,
                "SELECT id FROM document WHERE company_id = " + companyId + " AND deleted = 0");
        w.orderByDesc(SealApply::getCreatedAt).orderByDesc(SealApply::getId);

        int pn = (q.getPageNum() == null || q.getPageNum() < 1) ? 1 : q.getPageNum();
        int ps = (q.getPageSize() == null || q.getPageSize() < 1) ? 20 : Math.min(q.getPageSize(), MAX_PAGE_SIZE);
        Page<SealApply> page = applyMapper.selectPage(new Page<>(pn, ps), w);
        return PageResult.of(page.getTotal(), pn, ps, toVOs(page.getRecords()));
    }

    /**
     * 某张单据的用印状态 + 动作台账。
     *
     * <p>没有登记过用印时**不返回 404**，而是返回一条 {@code id == null}、状态为「待用印」的
     * 派生视图 —— 界面要能显示"还没用印 + 登记用印按钮"，而 404 会被前端当成故障。
     */
    public SealLedgerVO byDocument(Long documentId) {
        Document doc = requireSealDocument(documentId);
        SealApply apply = findApply(documentId);
        SealLedgerVO vo = (apply == null) ? deriveVO(doc) : toVO(apply, doc);
        List<SealRecord> records = recordMapper.selectList(Wrappers.<SealRecord>lambdaQuery()
                .eq(apply != null, SealRecord::getSealApplyId, apply == null ? null : apply.getId())
                .eq(apply == null, SealRecord::getDocumentId, documentId)
                .orderByAsc(SealRecord::getActionAt)
                .orderByAsc(SealRecord::getId));
        vo.setRecords(records);
        return vo;
    }

    /* ================================================================== 动作 */

    /** 登记用印：待用印 → 已用印 */
    @Transactional(rollbackFor = Exception.class)
    public SealLedgerVO use(SealActionReq req) {
        return act(req, true);
    }

    /** 归还：已用印 → 已归还 */
    @Transactional(rollbackFor = Exception.class)
    public SealLedgerVO returnSeal(SealActionReq req) {
        return act(req, false);
    }

    private SealLedgerVO act(SealActionReq req, boolean isUse) {
        Document doc = requireSealDocument(req.getDocumentId());
        assertStatusSealable(doc);

        SealApply apply = findApply(doc.getId());
        if (apply == null) {
            if (!isUse) {
                throw BizException.of("单据「%s」还没有登记用印，不能直接归还", doc.getDocNo());
            }
            apply = deriveFromDocument(doc);
            applyMapper.insert(apply);
        }

        int from = apply.getReturnStatus() == null ? ST_WAIT_USE : apply.getReturnStatus();
        if (isUse) {
            if (from == ST_USED) {
                throw BizException.of("单据「%s」已登记用印（%s），无需重复登记", doc.getDocNo(), text(apply.getSealTime()));
            }
            if (from == ST_RETURNED) {
                throw BizException.of("单据「%s」已归还，用印闭环已结束", doc.getDocNo());
            }
        } else {
            if (from == ST_WAIT_USE) {
                throw BizException.of("单据「%s」还没有用印记录，不能直接归还", doc.getDocNo());
            }
            if (from == ST_RETURNED) {
                throw BizException.of("单据「%s」已归还（%s），无需重复归还", doc.getDocNo(), text(apply.getReturnAt()));
            }
        }

        int to = isUse ? ST_USED : ST_RETURNED;
        LocalDateTime now = LocalDateTime.now();
        // 带条件的 UPDATE：把"期望的当前状态"写进 WHERE，防并发下两个请求都成功
        int affected = applyMapper.update(null, Wrappers.<SealApply>lambdaUpdate()
                .eq(SealApply::getId, apply.getId())
                .eq(SealApply::getReturnStatus, from)
                .set(SealApply::getReturnStatus, to)
                .set(isUse, SealApply::getSealTime, now)
                .set(!isUse, SealApply::getReturnAt, now)
                .set(SealApply::getUpdatedBy, UserContext.currentUserId()));
        if (affected == 0) {
            throw BizException.of("单据「%s」的用印状态刚刚被其他人变更，请刷新后重试", doc.getDocNo());
        }

        SealRecord rec = new SealRecord();
        rec.setSealApplyId(apply.getId());
        rec.setDocumentId(doc.getId());
        rec.setAction(isUse ? ACTION_USE : ACTION_RETURN);
        rec.setOperatorId(UserContext.currentUserId());
        rec.setOperatorName(UserContext.require().getRealName());
        rec.setActionAt(now);
        rec.setRemark(StringUtils.hasText(req.getRemark()) ? req.getRemark().trim() : null);
        rec.setCreatedBy(UserContext.currentUserId());
        recordMapper.insert(rec);

        log.info("用印台账 {} 单据={} 申请人={} 操作人={} 备注={}",
                isUse ? "登记用印" : "归还", doc.getDocNo(), doc.getApplicantName(),
                UserContext.require().getRealName(), req.getRemark());
        return byDocument(doc.getId());
    }

    /* ================================================================== 内部 */

    /** 必须存在、属于当前公司、且是 SEAL 类业务单据 */
    private Document requireSealDocument(Long documentId) {
        Document doc = documentMapper.selectById(documentId);
        if (doc == null) {
            throw BizException.notFound("单据不存在: " + documentId);
        }
        Long companyId = UserContext.require().getCompanyId();
        if (companyId != null && !companyId.equals(doc.getCompanyId())) {
            throw BizException.forbidden("单据不属于当前公司: " + documentId);
        }
        if (!BIZ_SEAL.equalsIgnoreCase(doc.getBusinessCategory())) {
            throw BizException.of("单据「%s」不是用印类单据（业务类别=%s），不能登记用印",
                    doc.getDocNo(), doc.getBusinessCategory());
        }
        return doc;
    }

    private void assertStatusSealable(Document doc) {
        Integer st = doc.getStatus();
        if (st == null || !SEALABLE_STATUS.contains(st)) {
            throw BizException.of("单据「%s」当前状态不允许用印（草稿、已驳回、已撤回的单据必须走完审批）",
                    doc.getDocNo());
        }
    }

    private SealApply findApply(Long documentId) {
        return applyMapper.selectOne(Wrappers.<SealApply>lambdaQuery()
                .eq(SealApply::getDocumentId, documentId)
                .last("LIMIT 1"));
    }

    /**
     * 从单据派生用印申请。
     *
     * <p>用印申请的字段本来就在单据的 {@code form_data} 里（用印项目/用章类型/用印原因/文件名称），
     * 让它跟着单据走、而不是要求操作人再录一遍：重复录入既费事，又会与单据正文不一致。
     */
    private SealApply deriveFromDocument(Document doc) {
        Map<String, Object> fd = JsonColumn.toMap(doc.getFormData());
        String sealType = str(fd.get("sealType"));
        if (!StringUtils.hasText(sealType)) {
            // seal_type 是 NOT NULL，且是台账统计的主维度。宁可明确报错，也不要静默写个占位值 ——
            // 台账是给审计看的，一条错分类的数据会污染整类统计的可信度。
            throw BizException.of("单据「%s」缺少「用章类型」，无法登记用印（请检查该单据的表单数据）",
                    doc.getDocNo());
        }
        String project = str(fd.get("sealProject"));
        SealApply a = new SealApply();
        a.setDocumentId(doc.getId());
        a.setSealProject(StringUtils.hasText(project) ? project : doc.getTitle());
        a.setSealDeptId(doc.getDeptId());
        a.setSealType(sealType);
        a.setSealReason(firstNonBlank(str(fd.get("sealReason")), doc.getReason()));
        a.setFileName(str(fd.get("fileName")));
        a.setReturnStatus(ST_WAIT_USE);
        a.setCreatedBy(UserContext.currentUserId());
        return a;
    }

    private List<SealLedgerVO> toVOs(List<SealApply> applies) {
        if (applies == null || applies.isEmpty()) {
            return List.of();
        }
        List<Long> docIds = applies.stream().map(SealApply::getDocumentId).filter(Objects::nonNull).distinct().toList();
        Map<Long, Document> docs = docIds.isEmpty() ? Map.of()
                : documentMapper.selectBatchIds(docIds).stream()
                        .collect(Collectors.toMap(Document::getId, d -> d, (a, b) -> a));
        return applies.stream().map(a -> toVO(a, docs.get(a.getDocumentId()))).toList();
    }

    private SealLedgerVO toVO(SealApply a, Document doc) {
        SealLedgerVO vo = new SealLedgerVO();
        vo.setId(a.getId());
        vo.setDocumentId(a.getDocumentId());
        vo.setSealProject(a.getSealProject());
        vo.setSealType(a.getSealType());
        vo.setSealTypeName(sealTypeName(a.getSealType()));
        vo.setSealReason(a.getSealReason());
        vo.setFileName(a.getFileName());
        vo.setSealTime(a.getSealTime());
        int st = a.getReturnStatus() == null ? ST_WAIT_USE : a.getReturnStatus();
        vo.setReturnStatus(st);
        vo.setReturnStatusText(statusText(st));
        vo.setReturnAt(a.getReturnAt());
        vo.setCreatedAt(a.getCreatedAt());
        fillDoc(vo, doc);
        return vo;
    }

    /** 未登记用印时的派生视图：id 为空、状态待用印 */
    private SealLedgerVO deriveVO(Document doc) {
        Map<String, Object> fd = JsonColumn.toMap(doc.getFormData());
        SealLedgerVO vo = new SealLedgerVO();
        vo.setId(null);
        vo.setDocumentId(doc.getId());
        vo.setSealProject(firstNonBlank(str(fd.get("sealProject")), doc.getTitle()));
        vo.setSealType(str(fd.get("sealType")));
        vo.setSealTypeName(sealTypeName(vo.getSealType()));
        vo.setSealReason(firstNonBlank(str(fd.get("sealReason")), doc.getReason()));
        vo.setFileName(str(fd.get("fileName")));
        vo.setReturnStatus(ST_WAIT_USE);
        vo.setReturnStatusText(statusText(ST_WAIT_USE));
        fillDoc(vo, doc);
        return vo;
    }

    private void fillDoc(SealLedgerVO vo, Document doc) {
        if (doc == null) {
            return;
        }
        vo.setDocNo(doc.getDocNo());
        vo.setTitle(doc.getTitle());
        vo.setApplicantName(doc.getApplicantName());
        vo.setDeptName(doc.getDeptName());
    }

    private String sealTypeName(String code) {
        if (!StringUtils.hasText(code)) {
            return null;
        }
        return dictMapper.selectList(Wrappers.<SysDict>lambdaQuery()
                        .eq(SysDict::getDictType, DICT_SEAL_TYPE)
                        .eq(SysDict::getDictCode, code))
                .stream().map(SysDict::getDictLabel).findFirst().orElse(code);
    }

    public static String statusText(int st) {
        return switch (st) {
            case ST_WAIT_USE -> "待用印";
            case ST_USED -> "已用印";
            case ST_RETURNED -> "已归还";
            default -> "未知";
        };
    }

    private LocalDate parseDate(String v, String what) {
        if (!StringUtils.hasText(v)) {
            return null;
        }
        try {
            return LocalDate.parse(v.trim());
        } catch (DateTimeParseException e) {
            throw BizException.of("%s 格式应为 yyyy-MM-dd：%s", what, v);
        }
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }

    private String firstNonBlank(String a, String b) {
        return StringUtils.hasText(a) ? a : (StringUtils.hasText(b) ? b : null);
    }

    private String text(LocalDateTime t) {
        return t == null ? "时间未知" : t.toString().replace('T', ' ');
    }
}
