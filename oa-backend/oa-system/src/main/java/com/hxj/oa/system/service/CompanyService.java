package com.hxj.oa.system.service;

import com.hxj.oa.common.exception.BizException;
import com.hxj.oa.common.security.UserContext;
import com.hxj.oa.system.dto.CompanySaveReq;
import com.hxj.oa.system.dto.CompanyVO;
import com.hxj.oa.system.entity.Company;
import com.hxj.oa.system.mapper.CompanyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 公司信息。
 *
 * <p>这个服务只服务一个对象 —— 当前登录人所属的那家公司。系统是多租户结构
 * （{@code company_id} 贯穿所有业务表），但界面从来不需要"公司列表"，
 * 所以这里不提供"按 id 查公司"的入口：能按 id 查就必然要判"这个 id 是不是你的"，
 * 只有一个对象时这类判断可以整体省掉。
 */
@Service
@RequiredArgsConstructor
public class CompanyService {

    private final CompanyMapper companyMapper;

    /** 当前登录人所属公司；取不到直接报错（初始化后 company 必有且仅有数据） */
    public Company current() {
        Long companyId = UserContext.currentCompanyId();
        Company c = companyId == null ? null : companyMapper.selectById(companyId);
        if (c == null) {
            throw BizException.notFound("公司不存在: " + companyId);
        }
        return c;
    }

    /** 当前公司信息（视图对象） */
    public CompanyVO currentVO() {
        return toVO(current());
    }

    /**
     * 修改当前公司的名称与简称。
     *
     * <p>只改自己所属的那一家：请求体里没有公司 ID，也就不存在"改到别家"的可能，
     * 比"传 ID 再校验归属"少一个容易出错的环节。
     */
    @Transactional(rollbackFor = Exception.class)
    public CompanyVO update(CompanySaveReq req) {
        Company c = current();
        c.setName(req.getName().trim());
        c.setShortName(StringUtils.hasText(req.getShortName()) ? req.getShortName().trim() : null);
        c.setUpdatedBy(UserContext.currentUserId());
        companyMapper.updateById(c);
        return toVO(c);
    }

    private CompanyVO toVO(Company c) {
        CompanyVO vo = new CompanyVO();
        vo.setId(c.getId());
        vo.setCode(c.getCode());
        vo.setName(c.getName());
        vo.setShortName(c.getShortName());
        vo.setStatus(c.getStatus());
        return vo;
    }
}
