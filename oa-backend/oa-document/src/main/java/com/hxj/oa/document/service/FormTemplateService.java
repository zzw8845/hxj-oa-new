package com.hxj.oa.document.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hxj.oa.common.exception.BizException;
import com.hxj.oa.common.util.JsonColumn;
import com.hxj.oa.common.util.JsonUtils;
import com.hxj.oa.document.entity.FormFieldPermission;
import com.hxj.oa.document.entity.FormTemplate;
import com.hxj.oa.document.mapper.FormFieldPermissionMapper;
import com.hxj.oa.document.mapper.FormTemplateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

/**
 * 动态表单服务。
 *
 * 三个职责：
 * 1. 取生效模板（含版本，单据提交时快照）
 * 2. 按「节点 + 字段」裁剪 Schema（字段级权限，列级可见/可编辑）
 * 3. **服务端二次校验** —— 前端渲染的 Schema 不可信，提交时必须按模板重新校验，
 *    否则动态表单会变成绕过校验的后门。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FormTemplateService {

    /** 发起节点约定 key：该节点默认全字段可编辑 */
    private static final String START_NODE = "n1";

    private final FormTemplateMapper templateMapper;
    private final FormFieldPermissionMapper permissionMapper;

    public FormTemplate getEffective(Long docTypeId) {
        FormTemplate tpl = templateMapper.selectOne(Wrappers.<FormTemplate>lambdaQuery()
                .eq(FormTemplate::getDocTypeId, docTypeId)
                .eq(FormTemplate::getStatus, 1)
                .orderByDesc(FormTemplate::getVersion)
                .last("LIMIT 1"));
        if (tpl == null) {
            throw BizException.notFound("单据类型 " + docTypeId + " 未配置生效的表单模板");
        }
        return tpl;
    }

    public FormTemplate getById(Long id) {
        FormTemplate tpl = templateMapper.selectById(id);
        if (tpl == null) {
            throw BizException.notFound("表单模板不存在: " + id);
        }
        return tpl;
    }

    /**
     * 按节点裁剪 Schema。
     *
     * @param templateId 模板 ID
     * @param nodeKey    当前节点（发起传 n1）
     */
    public Map<String, Object> renderSchema(Long templateId, String nodeKey) {
        FormTemplate tpl = getById(templateId);
        Map<String, Object> schema = JsonColumn.toMap(tpl.getSchemaJson());
        Object rawFields = schema.get("fields");
        if (!(rawFields instanceof List<?> fields)) {
            return schema;
        }

        List<FormFieldPermission> perms = permissionMapper.selectList(
                Wrappers.<FormFieldPermission>lambdaQuery().eq(FormFieldPermission::getTemplateId, templateId));
        Map<String, FormFieldPermission> byNodeField = new HashMap<>();
        Map<String, FormFieldPermission> byWildcardField = new HashMap<>();
        for (FormFieldPermission p : perms) {
            if ("*".equals(p.getNodeKey())) {
                byWildcardField.put(p.getFieldKey(), p);
            } else if (p.getNodeKey().equals(nodeKey)) {
                byNodeField.put(p.getFieldKey(), p);
            }
        }

        boolean isStartNode = START_NODE.equals(nodeKey);
        List<Map<String, Object>> rendered = new ArrayList<>();
        for (Object o : fields) {
            if (!(o instanceof Map<?, ?> fieldRaw)) {
                continue;
            }
            //noinspection unchecked
            Map<String, Object> field = new LinkedHashMap<>((Map<String, Object>) fieldRaw);
            String fieldKey = JsonColumn.str(field, "key");
            if (fieldKey == null) {
                continue;
            }

            FormFieldPermission perm = byNodeField.getOrDefault(fieldKey, byWildcardField.get(fieldKey));
            boolean visible;
            boolean editable;
            if (perm != null) {
                visible = perm.getVisible() == null || perm.getVisible() == 1;
                editable = perm.getEditable() != null && perm.getEditable() == 1;
            } else {
                // 缺省：发起节点可编辑，其余节点只读可见
                visible = true;
                editable = isStartNode;
            }
            if (!visible) {
                continue;
            }
            field.put("editable", editable);
            rendered.add(field);
        }

        Map<String, Object> result = new LinkedHashMap<>(schema);
        result.put("fields", rendered);
        result.put("nodeKey", nodeKey);
        result.put("templateId", tpl.getId());
        result.put("templateVersion", tpl.getVersion());
        return result;
    }

    /**
     * 服务端二次校验：按模板 Schema 校验提交的表单值。
     *
     * @return 字段级错误列表，空列表表示通过
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, String>> validate(Long templateId, Map<String, Object> data) {
        List<Map<String, String>> errors = new ArrayList<>();
        if (data == null) {
            data = Collections.emptyMap();
        }
        FormTemplate tpl = getById(templateId);
        Map<String, Object> schema = JsonColumn.toMap(tpl.getSchemaJson());
        Object rawFields = schema.get("fields");
        if (!(rawFields instanceof List<?> fields)) {
            return errors;
        }

        for (Object o : fields) {
            if (!(o instanceof Map<?, ?> f)) {
                continue;
            }
            Map<String, Object> field = (Map<String, Object>) f;
            String key = JsonColumn.str(field, "key");
            String label = Optional.ofNullable(JsonColumn.str(field, "label")).orElse(key);
            String type = JsonColumn.str(field, "type");
            boolean required = Boolean.TRUE.equals(JsonColumn.toBool(field, "required"));
            Object value = data.get(key);

            boolean blank = value == null
                    || (value instanceof String s && s.isBlank())
                    || (value instanceof Collection<?> c && c.isEmpty());
            if (required && blank) {
                errors.add(err(label, "不能为空"));
                continue;
            }
            if (blank) {
                continue;
            }

            switch (type == null ? "" : type) {
                case "money", "number" -> {
                    BigDecimal num;
                    try {
                        num = new BigDecimal(String.valueOf(value));
                    } catch (NumberFormatException e) {
                        errors.add(err(label, "必须是数字"));
                        continue;
                    }
                    BigDecimal min = toBigDecimal(field.get("min"));
                    BigDecimal max = toBigDecimal(field.get("max"));
                    if (min != null && num.compareTo(min) < 0) {
                        errors.add(err(label, "不能小于 " + min.toPlainString()));
                    }
                    if (max != null && num.compareTo(max) > 0) {
                        errors.add(err(label, "不能大于 " + max.toPlainString()));
                    }
                    if ("money".equals(type) && num.scale() > 2) {
                        errors.add(err(label, "最多保留 2 位小数"));
                    }
                }
                case "text", "textarea" -> {
                    Integer maxLength = JsonColumn.toLong(field, "maxLength") == null
                            ? null : JsonColumn.toLong(field, "maxLength").intValue();
                    if (maxLength != null && String.valueOf(value).length() > maxLength) {
                        errors.add(err(label, "长度不能超过 " + maxLength));
                    }
                }
                case "select" -> {
                    List<Map<String, Object>> options = (List<Map<String, Object>>) field.get("options");
                    if (options != null && !options.isEmpty()) {
                        boolean hit = options.stream()
                                .anyMatch(opt -> String.valueOf(value).equals(JsonColumn.str(opt, "value")));
                        if (!hit) {
                            errors.add(err(label, "取值不在允许范围内"));
                        }
                    }
                }
                default -> {
                    // attachment / invoiceGroup / date 等由前端保证，服务端只做空值校验
                }
            }
        }
        return errors;
    }

    private Map<String, String> err(String field, String message) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("field", field);
        m.put("message", message);
        return m;
    }

    private BigDecimal toBigDecimal(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public String schemaJsonOf(Long templateId) {
        return JsonUtils.toJson(renderSchema(templateId, START_NODE));
    }
}
