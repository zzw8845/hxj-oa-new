package com.hxj.oa.flow.controller;

import com.hxj.oa.common.annotation.Audit;
import com.hxj.oa.common.api.R;
import com.hxj.oa.common.security.UserContext;
import com.hxj.oa.flow.dto.DelegationSaveReq;
import com.hxj.oa.flow.dto.DelegationVO;
import com.hxj.oa.flow.service.DelegationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 审批委托（代理审批）。
 *
 * <p><b>刻意不挂权限点</b>：委托是**自助**行为 —— 每个人都能决定"我不在时谁替我审"，
 * 而这本身就是把权限交出去的按钮，所以"谁能用"的答案只能是"本人"。
 * 越权的防线落在数据上：请求体里没有 delegatorId（委托人恒为登录用户），
 * 撤销时校验 {@code delegator_id == 当前用户}。
 */
@RestController
@RequestMapping("/api/delegations")
@RequiredArgsConstructor
public class DelegationController {

    private final DelegationService delegationService;

    /** 我设置的委托 */
    @GetMapping("/mine")
    public R<List<DelegationVO>> mine() {
        return R.ok(delegationService.listMine(UserContext.require()));
    }

    /** 委托给我的 */
    @GetMapping("/to-me")
    public R<List<DelegationVO>> toMe() {
        return R.ok(delegationService.listToMe(UserContext.require()));
    }

    /** 新建委托（委托人 = 当前登录用户） */
    @PostMapping
    @Audit(module = "flow", action = "createDelegation")
    public R<DelegationVO> create(@Valid @RequestBody DelegationSaveReq req) {
        return R.ok(delegationService.create(req, UserContext.require()), "委托已生效");
    }

    /** 撤销委托（只能撤销自己设置的） */
    @DeleteMapping("/{id}")
    @Audit(module = "flow", action = "cancelDelegation")
    public R<Void> cancel(@PathVariable Long id) {
        delegationService.cancel(id, UserContext.require());
        return R.ok(null, "已撤销");
    }
}
