package com.hxj.oa.document.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hxj.oa.common.exception.BizException;
import com.hxj.oa.common.security.UserContext;
import com.hxj.oa.common.util.JsonColumn;
import com.hxj.oa.common.util.JsonUtils;
import com.hxj.oa.document.dto.FormTemplateDetailVO;
import com.hxj.oa.document.dto.FormTemplateSaveReq;
import com.hxj.oa.document.entity.DocumentType;
import com.hxj.oa.document.entity.FormFieldPermission;
import com.hxj.oa.document.entity.FormTemplate;
import com.hxj.oa.document.mapper.DocumentTypeMapper;
import com.hxj.oa.document.mapper.FormFieldPermissionMapper;
import com.hxj.oa.document.mapper.FormTemplateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 表单模板维护（写侧）。
 *
 * <p><b>此前的问题</b>：{@code form_template} / {@code form_field_permission} 两张表都在，
 * 但 `/api/forms/*` 只有 schema(读)、templates(读)、validate(校验) 三个接口 ——
 * **模板只能改库**。而原型 §9 把「表单模板驱动」列为关键架构决策，
 * 没有维护入口，动态表单落不了地。
 *
 * <h3>不变量一：改模板必须新建版本（最要紧的一条）</h3>
 * {@code document} 存了 {@code form_template_ver} 快照，但**提交时只存了版本号，没存 schema**。
 * 所以一旦就地修改生效中的模板，**在途单据的表单会跟着变**：字段被删掉时，
 * 单据的 form_data 里那些键就成了"没人认识的孤儿"，详情页渲染不出来还查不出原因。
 * 因此：{@code update} 只允许改**草稿**；要改生效版本，必须新建版本（复制一份改）再启用。
 * 这条与流程配置"改结构必须新建版本"是同一类约束。
 *
 * <h3>不变量二：同一单据类型同时只能有一个生效版本</h3>
 * 启用某版本时，把同单据类型下其它生效版本置为「废弃」并写 {@code effective_to}。
 * 否则 {@code getEffective} 取的是 version 最大的一条，界面上会以为生效的是另一个。
 *
 * <h3>不变量三：版本号由服务端分配</h3>
 * 取同单据类型当前最大版本 +1。让调用方指定的话，并发或手滑都会撞
 * {@code uk_form_tpl(doc_type_id, version, deleted)}，而报出来的是 500。
 *
 * <h3>不变量四：schema 必须结构化校验</h3>
 * 字段 key 唯一、type 在白名单内、label 非空、rules 引用的字段确实存在。
 * schema 是前端渲染与提交校验的共同依据，写坏了要么渲染崩、要么提交永远校验不过 ——
 * 都属于"现象与根因离得很远"的问题，所以宁可在入口挡住。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FormAdminService {

    /** 与 FormTemplateService#validate 的 switch 保持一致（多一个都会在提交时落到 default 分支不校验） */
    private static final Set<String> FIELD_TYPES = Set.of(
            "text", "textarea", "number", "money", "date", "select", "invoiceGroup", "attachment");

    public static final int ST_DRAFT = 0;
    public static final int ST_ACTIVE = 1;
    public static final int ST_RETIRED = 2;

    private final FormTemplateMapper templateMapper;
    private final FormFieldPermissionMapper permissionMapper;
    private final DocumentTypeMapper docTypeMapper;

    /* ================================================================== 查询 */

    public FormTemplateDetailVO detail(Long id) {
        FormTemplate tpl = requireTemplate(id);
        FormTemplateDetailVO vo = new FormTemplateDetailVO();
        vo.setId(tpl.getId());
        vo.setDocTypeId(tpl.getDocTypeId());
        DocumentType dt = docTypeMapper.selectById(tpl.getDocTypeId());
        vo.setDocTypeName(dt == null ? null : dt.getName());
        vo.setName(tpl.getName());
        vo.setVersion(tpl.getVersion());
        vo.setStatus(tpl.getStatus());
        vo.setStatusText(statusText(tpl.getStatus()));
        vo.setEditable(Objects.equals(tpl.getStatus(), ST_DRAFT));
        vo.setSchema(JsonColumn.toMap(tpl.getSchemaJson()));
        vo.setFieldPermissions(permissionsOf(id));
        return vo;
    }

    public List<Map<String, Object>> permissionsOf(Long templateId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (FormFieldPermission p : permissionMapper.selectList(Wrappers.<FormFieldPermission>lambdaQuery()
                .eq(FormFieldPermission::getTemplateId, templateId)
                .orderByAsc(FormFieldPermission::getNodeKey)
                .orderByAsc(FormFieldPermission::getFieldKey))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nodeKey", p.getNodeKey());
            m.put("fieldKey", p.getFieldKey());
            m.put("visible", p.getVisible());
            m.put("editable", p.getEditable());
            out.add(m);
        }
        return out;
    }

    /* ================================================================== 写 */

    /** 新建模板：一律落成**草稿**，版本号由服务端分配 */
    @Transactional(rollbackFor = Exception.class)
    public FormTemplateDetailVO create(FormTemplateSaveReq req) {
        DocumentType dt = requireDocType(req.getDocTypeId());
        validateSchema(req.getSchema());

        FormTemplate tpl = new FormTemplate();
        tpl.setCompanyId(UserContext.require().getCompanyId());
        tpl.setDocTypeId(dt.getId());
        tpl.setName(req.getName().trim());
        tpl.setVersion(nextVersion(dt.getId()));
        tpl.setSchemaJson(JsonUtils.toJson(req.getSchema()));
        tpl.setStatus(ST_DRAFT);
        tpl.setCreatedBy(UserContext.currentUserId());
        templateMapper.insert(tpl);

        if (req.getFieldPermissions() != null) {
            overwritePermissions(tpl, req);
        }
        log.info("新建表单模板 id={} docType={} version={} 操作人={}",
                tpl.getId(), dt.getCode(), tpl.getVersion(), UserContext.currentUserId());
        return detail(tpl.getId());
    }

    /** 改模板：**只允许改草稿**（生效版本要改必须新建版本，理由见类注释） */
    @Transactional(rollbackFor = Exception.class)
    public FormTemplateDetailVO update(Long id, FormTemplateSaveReq req) {
        FormTemplate tpl = requireTemplate(id);
        if (!Objects.equals(tpl.getStatus(), ST_DRAFT)) {
            throw BizException.of("模板「%s」v%s 当前是「%s」状态，不允许修改。"
                            + "已生效的模板改动会让在途单据的表单跟着变（字段被删时旧数据会变成读不出来的孤儿），"
                            + "请改为「新建版本」后再启用。",
                    tpl.getName(), tpl.getVersion(), statusText(tpl.getStatus()));
        }
        validateSchema(req.getSchema());

        FormTemplate upd = new FormTemplate();
        upd.setId(id);
        upd.setName(req.getName().trim());
        upd.setSchemaJson(JsonUtils.toJson(req.getSchema()));
        upd.setUpdatedBy(UserContext.currentUserId());
        templateMapper.updateById(upd);

        if (req.getFieldPermissions() != null) {
            overwritePermissions(tpl, req);
        }
        log.info("编辑表单模板 id={} name={} 操作人={}", id, req.getName(), UserContext.currentUserId());
        return detail(id);
    }

    /** 启用：草稿 → 生效，同时把同单据类型下其它生效版本置为废弃 */
    @Transactional(rollbackFor = Exception.class)
    public FormTemplateDetailVO activate(Long id) {
        FormTemplate tpl = requireTemplate(id);
        if (Objects.equals(tpl.getStatus(), ST_ACTIVE)) {
            throw BizException.of("模板「%s」v%s 已经是生效状态", tpl.getName(), tpl.getVersion());
        }
        /* 废弃版本**允许重新启用**（回退）。
           一开始我写的是"废弃不能启用"，但那样新版一旦有问题就**退不回旧版** ——
           而回退是发布系统的基本能力，比"防止误操作"重要得多。
           真正必须守住的只有一条：同一单据类型同时只能有一个生效版本（下面那段）。 */
        validateSchema(JsonColumn.toMap(tpl.getSchemaJson()));

        LocalDateTime now = LocalDateTime.now();
        List<FormTemplate> others = templateMapper.selectList(Wrappers.<FormTemplate>lambdaQuery()
                .eq(FormTemplate::getDocTypeId, tpl.getDocTypeId())
                .eq(FormTemplate::getStatus, ST_ACTIVE)
                .ne(FormTemplate::getId, id));
        for (FormTemplate old : others) {
            templateMapper.update(null, Wrappers.<FormTemplate>lambdaUpdate()
                    .eq(FormTemplate::getId, old.getId())
                    .set(FormTemplate::getStatus, ST_RETIRED)
                    .set(FormTemplate::getEffectiveTo, now)
                    .set(FormTemplate::getUpdatedBy, UserContext.currentUserId()));
            log.info("表单模板随新版启用而废弃 id={} v={}", old.getId(), old.getVersion());
        }

        templateMapper.update(null, Wrappers.<FormTemplate>lambdaUpdate()
                .eq(FormTemplate::getId, id)
                .set(FormTemplate::getStatus, ST_ACTIVE)
                .set(FormTemplate::getEffectiveFrom, now)
                .set(FormTemplate::getEffectiveTo, null)
                .set(FormTemplate::getUpdatedBy, UserContext.currentUserId()));

        log.info("启用表单模板 id={} docTypeId={} v={} 同时废弃 {} 个旧版本",
                id, tpl.getDocTypeId(), tpl.getVersion(), others.size());
        return detail(id);
    }

    /** 字段级权限：全量覆盖 */
    @Transactional(rollbackFor = Exception.class)
    public FormTemplateDetailVO updatePermissions(Long id, List<FormTemplateSaveReq.FieldPerm> perms) {
        FormTemplate tpl = requireTemplate(id);
        overwritePermissions(tpl, wrap(perms));
        log.info("配置表单字段权限 templateId={} 条数={} 操作人={}", id,
                perms == null ? 0 : perms.size(), UserContext.currentUserId());
        return detail(id);
    }

    /** 删除：**只允许删草稿**（生效/废弃版本是历史凭据，删了就无法解释在途单据用的是哪版） */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        FormTemplate tpl = requireTemplate(id);
        if (!Objects.equals(tpl.getStatus(), ST_DRAFT)) {
            throw BizException.of("模板「%s」v%s 是「%s」状态，不允许删除（只有草稿可删）",
                    tpl.getName(), tpl.getVersion(), statusText(tpl.getStatus()));
        }
        permissionMapper.physicalDeleteByTemplateId(id);
        /* 草稿**物理删除**：它是从未生效过的配置，没有任何单据引用它。
           逻辑删除在这里会留下隐患 —— uk_form_tpl 把 deleted 纳入了唯一键，
           于是"建 v3 → 删 v3 → 再建 v3 → 再删 v3"第二次删除会撞键报 500
           （与 user/dept 编码那个坑同源；但 version 是 int，追不了 "#D<id>" 后缀，
            所以这里选择"物理删除"而不是"让位"）。 */
        templateMapper.physicalDeleteById(id);
        log.info("删除表单模板（草稿，物理删除）id={} name={} v={} 操作人={}",
                id, tpl.getName(), tpl.getVersion(), UserContext.currentUserId());
    }

    /* ================================================================== 内部 */

    private FormTemplate requireTemplate(Long id) {
        FormTemplate tpl = templateMapper.selectById(id);
        if (tpl == null) {
            throw BizException.notFound("表单模板不存在: " + id);
        }
        return tpl;
    }

    private DocumentType requireDocType(Long docTypeId) {
        DocumentType dt = docTypeId == null ? null : docTypeMapper.selectById(docTypeId);
        if (dt == null) {
            throw BizException.notFound("单据类型不存在: " + docTypeId);
        }
        return dt;
    }

    private int nextVersion(Long docTypeId) {
        FormTemplate latest = templateMapper.selectOne(Wrappers.<FormTemplate>lambdaQuery()
                .eq(FormTemplate::getDocTypeId, docTypeId)
                .orderByDesc(FormTemplate::getVersion)
                .last("LIMIT 1"));
        return latest == null || latest.getVersion() == null ? 1 : latest.getVersion() + 1;
    }

    private FormTemplateSaveReq wrap(List<FormTemplateSaveReq.FieldPerm> perms) {
        FormTemplateSaveReq req = new FormTemplateSaveReq();
        req.setFieldPermissions(perms);
        return req;
    }

    /** 全量覆盖字段权限；先校验字段确实存在于 schema 里 */
    private void overwritePermissions(FormTemplate tpl, FormTemplateSaveReq req) {
        List<FormTemplateSaveReq.FieldPerm> perms = req.getFieldPermissions();
        Set<String> fieldKeys = fieldKeysOf(JsonColumn.toMap(tpl.getSchemaJson()));
        permissionMapper.physicalDeleteByTemplateId(tpl.getId());
        if (perms == null || perms.isEmpty()) {
            return;
        }
        Set<String> seen = new HashSet<>();
        Long operator = UserContext.currentUserId();
        for (FormTemplateSaveReq.FieldPerm p : perms) {
            String node = p.getNodeKey() == null ? "" : p.getNodeKey().trim();
            String field = p.getFieldKey() == null ? "" : p.getFieldKey().trim();
            if (node.isEmpty() || field.isEmpty()) {
                throw BizException.of("字段权限的「节点标识」「字段标识」都不能为空");
            }
            if (!fieldKeys.contains(field)) {
                // 配一个 schema 里不存在的字段：这条权限永远不会生效，而界面上看起来"配了"
                throw BizException.of("字段「%s」不在该模板的 schema 里，无法配置权限（可选字段：%s）",
                        field, String.join("、", fieldKeys));
            }
            if (!seen.add(node + "|" + field)) {
                throw BizException.of("字段权限重复：节点 %s 的字段 %s 配了两次", node, field);
            }
            FormFieldPermission entity = new FormFieldPermission();
            entity.setTemplateId(tpl.getId());
            entity.setNodeKey(node);
            entity.setFieldKey(field);
            entity.setVisible(p.getVisible() == null || p.getVisible() ? 1 : 0);
            entity.setEditable(p.getEditable() != null && p.getEditable() ? 1 : 0);
            permissionMapper.insert(entity);
        }
    }

    @SuppressWarnings("unchecked")
    private Set<String> fieldKeysOf(Map<String, Object> schema) {
        Set<String> keys = new HashSet<>();
        Object raw = schema == null ? null : schema.get("fields");
        if (raw instanceof List<?> fields) {
            for (Object f : fields) {
                if (f instanceof Map<?, ?> m) {
                    Object k = ((Map<String, Object>) m).get("key");
                    if (k != null && StringUtils.hasText(String.valueOf(k))) {
                        keys.add(String.valueOf(k).trim());
                    }
                }
            }
        }
        return keys;
    }

    /**
     * schema 结构化校验。宁可在入口挡住：schema 写坏了表现为"表单渲染崩"或
     * "提交永远校验不过"，现象与根因离得很远。
     */
    @SuppressWarnings("unchecked")
    private void validateSchema(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            throw BizException.of("表单 Schema 不能为空");
        }
        Object rawFields = schema.get("fields");
        if (!(rawFields instanceof List<?> fields) || fields.isEmpty()) {
            throw BizException.of("表单 Schema 至少要有一个字段（fields）");
        }
        Set<String> keys = new HashSet<>();
        for (Object o : fields) {
            if (!(o instanceof Map<?, ?> m)) {
                throw BizException.of("表单 Schema 的 fields 里存在非对象元素");
            }
            Map<String, Object> f = (Map<String, Object>) m;
            String key = str(f.get("key"));
            String type = str(f.get("type"));
            String label = str(f.get("label"));
            if (!StringUtils.hasText(key)) {
                throw BizException.of("表单字段缺少 key");
            }
            if (!keys.add(key)) {
                throw BizException.of("表单字段 key 重复：%s", key);
            }
            if (!StringUtils.hasText(type) || !FIELD_TYPES.contains(type)) {
                throw BizException.of("字段「%s」的类型「%s」不被支持（可用：%s）",
                        key, type, String.join("、", FIELD_TYPES));
            }
            if (!StringUtils.hasText(label)) {
                throw BizException.of("字段「%s」缺少 label（界面要显示它）", key);
            }
        }
        Object rawRules = schema.get("rules");
        if (rawRules instanceof List<?> rules) {
            for (Object o : rules) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                Map<String, Object> rule = (Map<String, Object>) m;
                if (!StringUtils.hasText(str(rule.get("when")))) {
                    throw BizException.of("显隐规则缺少 when 表达式");
                }
                Object then = rule.get("then");
                if (then instanceof Map<?, ?> t) {
                    Object show = ((Map<String, Object>) t).get("show");
                    if (show instanceof List<?> showKeys) {
                        for (Object sk : showKeys) {
                            String name = String.valueOf(sk).trim();
                            // 规则引用了不存在的字段：这条规则点了没用，而界面上看不出问题
                            if (!keys.contains(name)) {
                                throw BizException.of("显隐规则引用了不存在的字段「%s」（可选：%s）",
                                        name, String.join("、", keys));
                            }
                        }
                    }
                }
            }
        }
    }

    public static String statusText(Integer st) {
        int v = st == null ? ST_DRAFT : st;
        return switch (v) {
            case ST_DRAFT -> "草稿";
            case ST_ACTIVE -> "生效";
            case ST_RETIRED -> "废弃";
            default -> "未知";
        };
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }
}
