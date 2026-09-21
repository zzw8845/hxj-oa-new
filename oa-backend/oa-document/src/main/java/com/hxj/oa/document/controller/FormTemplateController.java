package com.hxj.oa.document.controller;

import com.hxj.oa.common.api.R;
import com.hxj.oa.document.entity.FormTemplate;
import com.hxj.oa.document.service.FormTemplateService;
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
}
