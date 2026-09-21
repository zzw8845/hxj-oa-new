package com.hxj.oa.flow.controller;

import com.hxj.oa.common.annotation.Audit;
import com.hxj.oa.common.api.R;
import com.hxj.oa.common.exception.BizException;
import com.hxj.oa.common.security.LoginUser;
import com.hxj.oa.common.security.RequirePerm;
import com.hxj.oa.common.security.UserContext;
import com.hxj.oa.flow.dto.AssigneeContext;
import com.hxj.oa.flow.dto.FlowPreviewVO;
import com.hxj.oa.flow.dto.FlowSaveReq;
import com.hxj.oa.flow.entity.FlowConfig;
import com.hxj.oa.flow.entity.FlowConfigNode;
import com.hxj.oa.flow.entity.FlowNodeAssignee;
import com.hxj.oa.flow.service.FlowConfigAdminService;
import com.hxj.oa.flow.service.FlowConfigService;
import com.hxj.oa.flow.service.FlowDeployService;
import com.hxj.oa.flow.service.FlowNodeTemplate;
import com.hxj.oa.system.entity.SysUser;
import com.hxj.oa.system.service.UserService;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 流程配置与预览。
 *
 * <p>读接口对所有登录用户开放（单据详情要展示审批链路）；
 * <b>写接口挂 {@code system:flow} 权限点</b>。
 */
@RestController
@RequestMapping("/api/flows")
@RequiredArgsConstructor
public class FlowConfigController {

    private final FlowConfigService flowConfigService;
    private final FlowConfigAdminService flowConfigAdminService;
    private final FlowDeployService flowDeployService;
    private final UserService userService;

    /* ------------------------------------------------------------------ 读 */

    @GetMapping("/configs")
    public R<List<FlowConfig>> list(@RequestParam(required = false) Long docTypeId) {
        return R.ok(flowConfigService.listByDocType(docTypeId));
    }

    @GetMapping("/configs/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        FlowConfig cfg = flowConfigService.getById(id);
        List<FlowConfigNode> nodes = flowConfigService.nodesOf(id);
        Map<String, Object> resp = new HashMap<>();
        resp.put("config", cfg);
        resp.put("nodes", nodes);
        resp.put("assignees", flowConfigService.assigneesOf(nodes));
        return R.ok(resp);
    }

    /** 查看生成的 BPMN XML（审计与排障用） */
    @GetMapping(value = "/configs/{id}/bpmn", produces = "application/xml;charset=UTF-8")
    public String bpmn(@PathVariable Long id) {
        FlowConfig cfg = flowConfigService.getById(id);
        if (cfg.getBpmnXml() == null || cfg.getBpmnXml().isBlank()) {
            throw BizException.of("流程「%s」尚未生成/部署 BPMN", cfg.getName());
        }
        return cfg.getBpmnXml();
    }

    /**
     * 流程预览：给定单据上下文，算出每个节点实际会由谁审。
     * applicantId 不传则用当前登录用户。
     */
    @PostMapping("/configs/{id}/preview")
    public R<FlowPreviewVO> preview(@PathVariable Long id, @RequestBody(required = false) PreviewRequest req) {
        LoginUser me = UserContext.require();
        PreviewRequest r = req == null ? new PreviewRequest() : req;
        Long applicantId = r.getApplicantId() == null ? me.getUserId() : r.getApplicantId();
        SysUser applicant = userService.getById(applicantId);

        AssigneeContext ctx = AssigneeContext.builder()
                .companyId(me.getCompanyId())
                .applicantId(applicantId)
                .applicantDeptId(applicant.getDeptId())
                .bizCategory(r.getBizCategory())
                .amount(r.getAmount())
                .formData(r.getFormData() == null ? new HashMap<>() : r.getFormData())
                .build();
        return R.ok(flowConfigService.preview(id, ctx));
    }

    /** 流程节点上挂的指派规则明细 */
    @GetMapping("/configs/{id}/assignees")
    public R<Map<Long, List<FlowNodeAssignee>>> assignees(@PathVariable Long id) {
        return R.ok(flowConfigService.assigneesOf(flowConfigService.nodesOf(id)));
    }

    /**
     * 可选节点模板库。
     * 前端「审批节点」下拉用它渲染 —— 每个候选都对应后端一条确定的指派规则，
     * 避免用户自由输入一个名字、流程部署成功却找不到审批人。
     */
    @GetMapping("/node-templates")
    public R<List<FlowNodeTemplate.NodeTemplate>> nodeTemplates() {
        return R.ok(flowConfigAdminService.templates());
    }

    /* ------------------------------------------------------------------ 写 */

    @PostMapping("/configs")
    @RequirePerm("system:flow")
    @Audit(module = "flow", action = "createConfig")
    public R<FlowConfig> create(@Valid @RequestBody FlowSaveReq req) {
        return R.ok(flowConfigAdminService.create(req), "流程已创建并部署");
    }

    /** 修改流程结构：新建版本 + 重新部署；在途单据仍走旧版本，不受影响 */
    @PutMapping("/configs/{id}")
    @RequirePerm("system:flow")
    @Audit(module = "flow", action = "updateConfig")
    public R<FlowConfig> update(@PathVariable Long id, @Valid @RequestBody FlowSaveReq req) {
        return R.ok(flowConfigAdminService.update(id, req), "流程已保存为新版本并部署");
    }

    /** 只调整节点审批人：改规则表即生效，无需重新部署 */
    @PutMapping("/configs/{id}/assignees")
    @RequirePerm("system:flow")
    @Audit(module = "flow", action = "updateAssignees")
    public R<FlowConfig> updateAssignees(@PathVariable Long id,
                                         @RequestBody List<FlowConfigAdminService.NodeAssignReq> req) {
        return R.ok(flowConfigAdminService.updateAssignees(id, req), "审批人已更新，无需重新部署即生效");
    }

    /** 手动部署到 Flowable 引擎 */
    @PostMapping("/configs/{id}/deploy")
    @RequirePerm("system:flow")
    @Audit(module = "flow", action = "deploy")
    public R<FlowConfig> deploy(@PathVariable Long id) {
        return R.ok(flowDeployService.deploy(id), "部署成功");
    }

    /** 废弃流程（有在途单据时会拒绝） */
    @DeleteMapping("/configs/{id}")
    @RequirePerm("system:flow")
    @Audit(module = "flow", action = "retireConfig")
    public R<Void> retire(@PathVariable Long id) {
        flowConfigAdminService.retire(id);
        return R.ok(null, "流程已废弃");
    }

    @Data
    public static class PreviewRequest {
        private Long applicantId;
        private String bizCategory;
        private BigDecimal amount;
        private Map<String, Object> formData;
    }
}
