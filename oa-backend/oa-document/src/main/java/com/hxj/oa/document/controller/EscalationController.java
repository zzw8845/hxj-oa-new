package com.hxj.oa.document.controller;

import com.hxj.oa.common.annotation.Audit;
import com.hxj.oa.common.api.R;
import com.hxj.oa.common.security.RequirePerm;
import com.hxj.oa.document.dto.EscalationResultVO;
import com.hxj.oa.document.entity.FlowEscalation;
import com.hxj.oa.document.service.EscalationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 超时升级（节点超过处理时限后把上级加签进来）。
 *
 * <p><b>需求尚未确认</b>（用户明确说过"目前还不知道有无这个需求"），所以：
 * 自动升级默认关闭（{@code oa.flow.escalation.enabled}），
 * 这里的手动触发**不受开关限制** —— 它是运维的显式动作，
 * 也是验证用例做确定性检查的入口（不然只能等定时器，没法测）。
 */
@RestController
@RequestMapping("/api/flows/escalation")
@RequiredArgsConstructor
public class EscalationController {

    private final EscalationService escalationService;

    /**
     * 立即执行一次超时升级盘点（幂等：已升级过的节点不会重复升）。
     *
     * @param documentId 可选：只处理某张单据的节点。**建议运维用它**——
     *                   不传就是全局扫描，会把库里所有超时节点都动一遍。
     */
    @PostMapping("/run")
    @RequirePerm("system:flow")
    @Audit(module = "flow", action = "runEscalation")
    public R<EscalationResultVO> run(@RequestParam(required = false) Long documentId) {
        EscalationResultVO r = escalationService.escalateOverdue(documentId);
        return R.ok(r, String.format("扫描 %d 条，升级 %d 条", r.getScanned(), r.getEscalated()));
    }

    /** 某单据的升级记录（排查"这条待办为什么升级了"用） */
    @GetMapping("/by-document/{documentId}")
    @RequirePerm("system:flow")
    public R<List<FlowEscalation>> byDocument(@PathVariable Long documentId) {
        return R.ok(escalationService.historyOf(documentId));
    }
}
