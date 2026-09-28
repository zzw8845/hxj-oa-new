package com.hxj.oa.system.controller;

import com.hxj.oa.common.annotation.Audit;
import com.hxj.oa.common.api.R;
import com.hxj.oa.common.security.RequirePerm;
import com.hxj.oa.system.dto.CompanySaveReq;
import com.hxj.oa.system.dto.CompanyVO;
import com.hxj.oa.system.service.CompanyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公司信息。
 *
 * <p>这是「一切走应用」缺口的最后一块：此前 company 表有实体、有 Mapper，
 * 却没有任何接口，公司名只能在前端写死（左侧 logo、单据详情的「所属公司」都是硬编码），
 * 改个公司名得去改前端代码重新发版。
 */
@RestController
@RequestMapping("/api/company")
@RequiredArgsConstructor
public class CompanyController {

    private final CompanyService companyService;

    /**
     * 当前登录人所属公司信息。
     *
     * <p>不挂权限点：登录后立刻要用（左侧 logo 显示公司简称、单据详情里的「所属公司」），
     * 挂了会让普通员工看到 403 或空白。返回的是公司自身信息，不含任何人员数据。
     */
    @GetMapping
    public R<CompanyVO> current() {
        return R.ok(companyService.currentVO());
    }

    /**
     * 修改公司名称与简称。
     *
     * <p>⚠ 改的是<b>当前登录人所属的那家公司</b>（请求体里没有公司 ID，也就改不到别家）。
     * 简称留空 = 不使用简称，界面回退显示全称。
     */
    @PutMapping
    @RequirePerm("system:company")
    @Audit(module = "permission", action = "updateCompany")
    public R<CompanyVO> update(@Valid @RequestBody CompanySaveReq req) {
        return R.ok(companyService.update(req), "公司信息已更新");
    }
}
