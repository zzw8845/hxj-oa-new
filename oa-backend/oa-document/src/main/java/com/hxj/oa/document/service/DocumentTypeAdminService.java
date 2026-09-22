package com.hxj.oa.document.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hxj.oa.common.exception.BizException;
import com.hxj.oa.common.security.LoginUser;
import com.hxj.oa.document.dto.DocumentTypeSaveReq;
import com.hxj.oa.document.dto.DocumentTypeVO;
import com.hxj.oa.document.entity.DocumentType;
import com.hxj.oa.document.mapper.DocumentTypeMapper;
import com.hxj.oa.system.service.UniqueKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单据类型管理（主数据维护）。
 *
 * <p>三条铁律在这里落地：
 * <ol>
 *   <li><b>category 是系统级枚举</b>：合法值只有 {@link #CATEGORY_LABELS} 的键，
 *       管理员不能发明新的大类 —— 新大类意味着新行为（分区/编号前缀/专属台账），那是发版级改动；</li>
 *   <li><b>删除守卫</b>：被单据 / 表单模板 / 流程配置任一引用时拒绝删除
 *       （模板与流程按 doc_type_id 绑定，删了类型它们就成了孤儿）；</li>
 *   <li><b>逻辑删除先释放唯一键</b>：uk_doctype_code 含 deleted 列，
 *       直接置 deleted=1 会让「建→删→再建→再删」第二次删除撞键报 500，
 *       统一走 {@link UniqueKeys#release}（与角色/岗位删除同一处理）。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class DocumentTypeAdminService {

    /** 业务大类白名单：LinkedHashMap 保证前端下拉顺序稳定；标签由后端下发，前端不再写死映射 */
    public static final Map<String, String> CATEGORY_LABELS = new LinkedHashMap<>(Map.of(
            "DAILY", "日常付款",
            "BIZ", "业务付款",
            "REIMBURSE", "员工报销",
            "SEAL", "用印申请"));

    private final DocumentTypeMapper docTypeMapper;

    /* ---------- 查询 ---------- */

    /** 全量（含停用）：管理页用 */
    public List<DocumentTypeVO> listAll(Long companyId) {
        return docTypeMapper.selectList(Wrappers.<DocumentType>lambdaQuery()
                        .and(w -> w.eq(DocumentType::getCompanyId, companyId).or().isNull(DocumentType::getCompanyId))
                        .orderByAsc(DocumentType::getSortNo).orderByAsc(DocumentType::getId))
                .stream().map(this::toVo).toList();
    }

    /** 启用中的类型（发起菜单等业务入口用），带 categoryLabel */
    public List<DocumentTypeVO> listEnabled(Long companyId) {
        return docTypeMapper.selectList(Wrappers.<DocumentType>lambdaQuery()
                        .eq(DocumentType::getStatus, 1)
                        .and(w -> w.eq(DocumentType::getCompanyId, companyId).or().isNull(DocumentType::getCompanyId))
                        .orderByAsc(DocumentType::getSortNo))
                .stream().map(this::toVo).toList();
    }

    /** 业务大类白名单（供前端下拉渲染 —— 同样是后端下发，前端不写死） */
    public Map<String, String> categories() {
        return CATEGORY_LABELS;
    }

    /* ---------- 写操作 ---------- */

    public DocumentTypeVO create(DocumentTypeSaveReq req, LoginUser user) {
        assertCategory(req.getCategory());
        assertCodeFree(req.getCode(), null);
        assertNameFree(req.getName(), null);

        DocumentType dt = new DocumentType();
        dt.setCompanyId(user.getCompanyId());
        dt.setCode(req.getCode());
        dt.setName(req.getName());
        dt.setCategory(req.getCategory());
        dt.setMustLinkPrev(nz(req.getMustLinkPrev()));
        dt.setStatus(nz(req.getStatus()));
        dt.setSortNo(req.getSortNo() == null ? 0 : req.getSortNo());
        dt.setCreatedBy(user.getUserId());
        dt.setUpdatedBy(user.getUserId());
        docTypeMapper.insert(dt);
        return toVo(dt);
    }

    public DocumentTypeVO update(Long id, DocumentTypeSaveReq req) {
        DocumentType dt = requireDocType(id);
        assertCategory(req.getCategory());
        assertCodeFree(req.getCode(), id);
        assertNameFree(req.getName(), id);

        dt.setCode(req.getCode());
        dt.setName(req.getName());
        dt.setCategory(req.getCategory());
        dt.setMustLinkPrev(nz(req.getMustLinkPrev()));
        dt.setStatus(nz(req.getStatus()));
        dt.setSortNo(req.getSortNo() == null ? 0 : req.getSortNo());
        docTypeMapper.updateById(dt);
        return toVo(dt);
    }

    /**
     * 删除（逻辑删除）。被单据 / 表单模板 / 流程配置引用时拒绝 ——
     * 模板和流程都按 doc_type_id 关联，删类型会留下一堆孤儿配置。
     */
    public void delete(Long id) {
        DocumentType dt = requireDocType(id);

        long docCount = docTypeMapper.countDocuments(id);
        if (docCount > 0) {
            throw new BizException("该单据类型已有 " + docCount + " 张单据，不能删除（可改为停用）");
        }
        long tplCount = docTypeMapper.countTemplates(id);
        if (tplCount > 0) {
            throw new BizException("该单据类型已绑定 " + tplCount + " 个表单模板，请先删除模板再删除类型");
        }
        long flowCount = docTypeMapper.countFlowConfigs(id);
        if (flowCount > 0) {
            throw new BizException("该单据类型已绑定 " + flowCount + " 个审批流程，请先废弃流程再删除类型");
        }

        // 释放 uk_doctype_code 的 code，让位给将来同编码的新类型（铁律：逻辑删除前必须释放唯一键）
        DocumentType upd = new DocumentType();
        upd.setId(id);
        upd.setCode(UniqueKeys.release(dt.getCode(), id, 64));
        docTypeMapper.updateById(upd);
        docTypeMapper.deleteById(id);
    }

    /* ---------- 私有 ---------- */

    private DocumentTypeVO toVo(DocumentType dt) {
        DocumentTypeVO vo = new DocumentTypeVO();
        vo.setId(dt.getId());
        vo.setCompanyId(dt.getCompanyId());
        vo.setCode(dt.getCode());
        vo.setName(dt.getName());
        vo.setCategory(dt.getCategory());
        vo.setCategoryLabel(CATEGORY_LABELS.getOrDefault(dt.getCategory(), dt.getCategory()));
        vo.setMustLinkPrev(dt.getMustLinkPrev());
        vo.setStatus(dt.getStatus());
        vo.setSortNo(dt.getSortNo());
        return vo;
    }

    private DocumentType requireDocType(Long id) {
        DocumentType dt = id == null ? null : docTypeMapper.selectById(id);
        if (dt == null || dt.getDeleted() != null && dt.getDeleted() == 1) {
            throw BizException.notFound("单据类型不存在或已删除");
        }
        return dt;
    }

    private void assertCategory(String category) {
        if (!CATEGORY_LABELS.containsKey(category)) {
            throw new BizException("业务类型必须是 " + String.join(" / ", CATEGORY_LABELS.keySet()) + " 之一");
        }
    }

    private void assertCodeFree(String code, Long excludeId) {
        DocumentType hit = docTypeMapper.selectOne(Wrappers.<DocumentType>lambdaQuery()
                .eq(DocumentType::getCode, code)
                .ne(excludeId != null, DocumentType::getId, excludeId)
                .last("LIMIT 1"));
        if (hit != null) {
            throw new BizException("类型编码已存在：" + code);
        }
    }

    private void assertNameFree(String name, Long excludeId) {
        DocumentType hit = docTypeMapper.selectOne(Wrappers.<DocumentType>lambdaQuery()
                .eq(DocumentType::getName, name)
                .ne(excludeId != null, DocumentType::getId, excludeId)
                .last("LIMIT 1"));
        if (hit != null) {
            throw new BizException("类型名称已存在：" + name);
        }
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
