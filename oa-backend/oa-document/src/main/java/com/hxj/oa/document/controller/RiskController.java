package com.hxj.oa.document.controller;

import com.hxj.oa.common.api.R;
import com.hxj.oa.common.security.UserContext;
import com.hxj.oa.document.dto.RiskOverviewVO;
import com.hxj.oa.document.service.RiskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 风险预警：超期 / 即将到期的审批节点盘点。
 *
 * <p>不加 {@code @RequirePerm}：与首页看板同理，看到的是**自己数据范围内**的运营状态，
 * 范围本身已经由 {@code DataScopeHelper} 收敛，再叠一个静态权限点只会让部门负责人看不到自己部门的超期件。
 */
@RestController
@RequestMapping("/api/risks")
@RequiredArgsConstructor
public class RiskController {

    private final RiskService riskService;

    /** 当前用户可见范围内的风险总览 + 明细 */
    @GetMapping
    public R<RiskOverviewVO> overview() {
        return R.ok(riskService.overview(UserContext.require()));
    }
}
