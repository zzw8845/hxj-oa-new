package com.hxj.oa.document.controller;

import com.hxj.oa.common.annotation.Audit;
import com.hxj.oa.common.api.PageResult;
import com.hxj.oa.common.api.R;
import com.hxj.oa.common.security.RequirePerm;
import com.hxj.oa.document.dto.SealActionReq;
import com.hxj.oa.document.dto.SealLedgerVO;
import com.hxj.oa.document.dto.SealQuery;
import com.hxj.oa.document.service.SealService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 用印台账与归还闭环。
 *
 * <p>权限统一用 {@code document:approve:seal}（用印办理）—— 这个权限点**早就存在**，
 * 是"谁负责盖章"的既有定义，所以不新设权限点：新设一个就会让每个角色的配置多勾一项，
 * 而实际维护者是同一批人。
 *
 * <p>写操作补了 {@code @Audit(module = "seal")}：用印是内控上最需要留痕的动作之一，
 * 台账之外还要有审计层面的独立记录（两条链路互为佐证）。
 */
@RestController
@RequestMapping("/api/seals")
@RequiredArgsConstructor
public class SealController {

    private final SealService sealService;

    /**
     * 用印台账分页（按用章类型 / 归还状态 / 关键字 / 创建日期区间筛选）。
     *
     * @param query 筛选与分页条件（字段见 {@code SealQuery}）
     */
    @GetMapping
    @RequirePerm("document:approve:seal")
    public R<PageResult<SealLedgerVO>> page(SealQuery query) {
        return R.ok(sealService.page(query));
    }

    /**
     * 某张单据的用印状态 + 动作台账。
     *
     * <p>未登记用印时返回 id 为空的「待用印」视图而不是 404 —— 界面需要靠它渲染
     * "还没盖章 + 登记用印按钮"，404 会被前端当成故障弹错。
     */
    @GetMapping("/by-document/{documentId}")
    @RequirePerm("document:approve:seal")
    public R<SealLedgerVO> byDocument(@PathVariable Long documentId) {
        return R.ok(sealService.byDocument(documentId));
    }

    /** 登记用印：待用印 → 已用印 */
    @PostMapping("/use")
    @RequirePerm("document:approve:seal")
    @Audit(module = "seal", action = "useSeal")
    public R<SealLedgerVO> use(@Valid @RequestBody SealActionReq req) {
        return R.ok(sealService.use(req), "已登记用印");
    }

    /** 归还：已用印 → 已归还 */
    @PostMapping("/return")
    @RequirePerm("document:approve:seal")
    @Audit(module = "seal", action = "returnSeal")
    public R<SealLedgerVO> returnSeal(@Valid @RequestBody SealActionReq req) {
        return R.ok(sealService.returnSeal(req), "已登记归还");
    }
}
