package com.hxj.oa.document.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hxj.oa.common.annotation.Audit;
import com.hxj.oa.common.api.R;
import com.hxj.oa.common.security.RequirePerm;
import com.hxj.oa.document.dto.FormTemplateDetailVO;
import com.hxj.oa.document.dto.FormTemplateSaveReq;
import com.hxj.oa.document.entity.FormTemplate;
import com.hxj.oa.document.mapper.FormTemplateMapper;
import com.hxj.oa.document.service.FormAdminService;
import com.hxj.oa.document.service.FormTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 动态表单：按单据类型与节点取渲染 Schema */
@RestController
@RequestMapping("/api/forms")
@RequiredArgsConstructor
public class FormTemplateController {

    private final FormTemplateService formTemplateService;
    private final FormAdminService formAdminService;
    private final FormTemplateMapper templateMapper;

    /**
     * 取某单据类型的表单 Schema。
     *
     * @param docTypeId 单据类型
     * @param nodeKey   节点标识，默认 n1（发起）；传入当前审批节点则返回该节点的字段权限
     */
    @GetMapping("/schema")
    public R<Map<String, Object>> schema(@RequestParam Long docTypeId,
                                         @RequestParam(defaultValue = "n1") String nodeKey) {
        FormTemplate tpl = formTemplateService.getEffective(docTypeId);
        return R.ok(formTemplateService.renderSchema(tpl.getId(), nodeKey));
    }

    /** 模板原始定义（配置页用） */
    @GetMapping("/templates")
    public R<List<FormTemplate>> templates(@RequestParam Long docTypeId) {
        return R.ok(List.of(formTemplateService.getEffective(docTypeId)));
    }

    /** 服务端二次校验（前端提交前可预检，但提交时后端仍会再校验一次） */
    @PostMapping("/validate")
    public R<List<Map<String, String>>> validate(@RequestParam Long docTypeId,
                                                 @RequestBody Map<String, Object> formData) {
        FormTemplate tpl = formTemplateService.getEffective(docTypeId);
        List<Map<String, String>> errors = formTemplateService.validate(tpl.getId(), formData);
        return errors.isEmpty() ? R.ok(errors, "校验通过") : R.fail(422, "校验未通过");
    }

    /* ------------------------------------------------------------------ 维护
       写接口一律挂 {@code system:form}（表单配置）+ {@code @Audit(module = "system")}。
       注意：{@code GET /api/forms/templates} 只返回**生效**版本（历史行为，未改），
       配置页要看全部版本请用下面的 {@code /templates/versions}。 */

    /** 某单据类型的全部版本（草稿 / 生效 / 废弃）—— 配置页用 */
    @GetMapping("/templates/versions")
    @RequirePerm("system:form")
    public R<List<FormTemplate>> versions(@RequestParam Long docTypeId) {
        return R.ok(templateMapper.selectList(Wrappers.<FormTemplate>lambdaQuery()
                .eq(FormTemplate::getDocTypeId, docTypeId)
                .orderByDesc(FormTemplate::getVersion)));
    }

    /** 模板详情：schema 已解析 + 字段级权限 */
    @GetMapping("/templates/{id}")
    @RequirePerm("system:form")
    public R<FormTemplateDetailVO> detail(@PathVariable Long id) {
        return R.ok(formAdminService.detail(id));
    }

    /** 新建模板（落草稿，版本号由服务端分配） */
    @PostMapping("/templates")
    @RequirePerm("system:form")
    @Audit(module = "system", action = "createFormTemplate")
    public R<FormTemplateDetailVO> create(@Valid @RequestBody FormTemplateSaveReq req) {
        return R.ok(formAdminService.create(req));
    }

    /** 改模板（只允许改草稿；生效版本要改必须新建版本） */
    @PutMapping("/templates/{id}")
    @RequirePerm("system:form")
    @Audit(module = "system", action = "updateFormTemplate")
    public R<FormTemplateDetailVO> update(@PathVariable Long id,
                                          @Valid @RequestBody FormTemplateSaveReq req) {
        return R.ok(formAdminService.update(id, req));
    }

    /** 启用：草稿 → 生效，同时废弃同单据类型的旧生效版本 */
    @PostMapping("/templates/{id}/activate")
    @RequirePerm("system:form")
    @Audit(module = "system", action = "activateFormTemplate")
    public R<FormTemplateDetailVO> activate(@PathVariable Long id) {
        return R.ok(formAdminService.activate(id), "已启用");
    }

    /** 字段级权限：全量覆盖 */
    @PutMapping("/templates/{id}/field-permissions")
    @RequirePerm("system:form")
    @Audit(module = "system", action = "updateFormFieldPerm")
    public R<FormTemplateDetailVO> updatePermissions(
            @PathVariable Long id,
            @RequestBody List<FormTemplateSaveReq.FieldPerm> perms) {
        return R.ok(formAdminService.updatePermissions(id, perms));
    }

    /** 删除模板（只允许删草稿） */
    @DeleteMapping("/templates/{id}")
    @RequirePerm("system:form")
    @Audit(module = "system", action = "deleteFormTemplate")
    public R<Void> delete(@PathVariable Long id) {
        formAdminService.delete(id);
        return R.ok(null, "已删除");
    }
}
