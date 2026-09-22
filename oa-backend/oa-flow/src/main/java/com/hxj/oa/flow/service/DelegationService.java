package com.hxj.oa.flow.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hxj.oa.common.exception.BizException;
import com.hxj.oa.common.security.LoginUser;
import com.hxj.oa.flow.dto.DelegationSaveReq;
import com.hxj.oa.flow.dto.DelegationVO;
import com.hxj.oa.flow.entity.FlowDelegation;
import com.hxj.oa.flow.mapper.FlowDelegationMapper;
import com.hxj.oa.system.entity.SysUser;
import com.hxj.oa.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 审批委托（代理审批）。
 *
 * <p><b>生效方式</b>：受托人凭委托记录获得「看见该待办 + 处理该待办」的资格
 * （见 {@code TodoService#myTodo} 与 {@code FlowRuntimeService} 的授权判定）。
 * **不改写流程的指派结果** —— 节点 assignee 仍是原承办人，
 * 这样流程历史里"谁审的"不会被换人，也不会让分支条件（依赖 assignee）产生歧义。
 *
 * <p><b>业务类别写错只会让委托不生效，不会放宽权限</b>：类别是"窄化条件"，
 * 匹配不上就等于这条委托不存在。这是个 fail-safe 的方向，所以这里不做严格的类别白名单校验
 * （那需要在 oa-flow 里反向依赖 oa-document 的字典/类型表，代价更大）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DelegationService {

    public static final int ST_ACTIVE = 1;
    public static final int ST_CANCELLED = 0;

    private final FlowDelegationMapper delegationMapper;
    private final SysUserMapper userMapper;

    /* ================================================================== 查询 */

    /** 我设置的委托（委托人视角） */
    public List<DelegationVO> listMine(LoginUser user) {
        return toVOs(delegationMapper.selectList(Wrappers.<FlowDelegation>lambdaQuery()
                .eq(FlowDelegation::getDelegatorId, user.getUserId())
                .orderByDesc(FlowDelegation::getStartAt)
                .orderByDesc(FlowDelegation::getId)));
    }

    /** 委托给我的（受托人视角） */
    public List<DelegationVO> listToMe(LoginUser user) {
        return toVOs(delegationMapper.selectList(Wrappers.<FlowDelegation>lambdaQuery()
                .eq(FlowDelegation::getDelegateId, user.getUserId())
                .orderByDesc(FlowDelegation::getStartAt)
                .orderByDesc(FlowDelegation::getId)));
    }

    /**
     * 此刻委托给我的全部生效记录（待办聚合要用）。
     *
     * <p>返回带姓名的 VO 而不是实体：待办列表要标注「代 某某 办理」，
     * 让调用方自己再查一次用户表就又多了一处 N+1。
     */
    public List<DelegationVO> activeToMe(Long delegateId) {
        LocalDateTime now = LocalDateTime.now();
        return toVOs(delegationMapper.selectList(Wrappers.<FlowDelegation>lambdaQuery()
                .eq(FlowDelegation::getDelegateId, delegateId)
                .eq(FlowDelegation::getStatus, ST_ACTIVE)
                .le(FlowDelegation::getStartAt, now)
                .ge(FlowDelegation::getEndAt, now)));
    }

    /**
     * 受托人此刻能否代 {@code delegatorId} 处理 {@code bizCategory} 的单据。
     *
     * <p>判定要素缺一不可：委托存在、状态生效、在有效期内、类别匹配（未限定则不限）。
     * 这是**审批放行的唯一依据**，所以刻意做成一个方法而不是散在各处 ——
     * 两处不一致（待办看得见但批不了，或反之）是最难排查的一类问题。
     */
    public boolean canActFor(Long delegatorId, Long delegateId, String bizCategory) {
        if (delegatorId == null || delegateId == null || Objects.equals(delegatorId, delegateId)) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        return delegationMapper.selectCount(Wrappers.<FlowDelegation>lambdaQuery()
                .eq(FlowDelegation::getDelegatorId, delegatorId)
                .eq(FlowDelegation::getDelegateId, delegateId)
                .eq(FlowDelegation::getStatus, ST_ACTIVE)
                .le(FlowDelegation::getStartAt, now)
                .ge(FlowDelegation::getEndAt, now)
                .and(w -> w.isNull(FlowDelegation::getBizCategory)
                        .or().eq(FlowDelegation::getBizCategory, bizCategory == null ? "" : bizCategory)))
                > 0;
    }

    /* ================================================================== 写 */

    @Transactional(rollbackFor = Exception.class)
    public DelegationVO create(DelegationSaveReq req, LoginUser user) {
        Long me = user.getUserId();
        Long delegateId = req.getDelegateId();

        if (Objects.equals(me, delegateId)) {
            throw BizException.of("不能把审批委托给自己");
        }
        SysUser delegate = userMapper.selectById(delegateId);
        if (delegate == null) {
            throw BizException.notFound("受托人不存在: " + delegateId);
        }
        if (user.getCompanyId() != null && !user.getCompanyId().equals(delegate.getCompanyId())) {
            throw BizException.of("只能委托给本公司的人");
        }
        if (delegate.getStatus() != null && delegate.getStatus() != 1) {
            // 委托给已停用账号 = 待办掉进黑洞：受托人登录不了，委托人又以为有人代办
            throw BizException.of("「%s」已停用，不能作为受托人", delegate.getRealName());
        }
        if (!req.getStartAt().isBefore(req.getEndAt())) {
            throw BizException.of("生效开始时间必须早于结束时间");
        }
        String category = StringUtils.hasText(req.getBizCategory())
                ? req.getBizCategory().trim().toUpperCase() : null;
        assertNoOverlap(me, delegateId, category, req.getStartAt(), req.getEndAt());

        FlowDelegation d = new FlowDelegation();
        d.setCompanyId(user.getCompanyId());
        d.setDelegatorId(me);
        d.setDelegateId(delegateId);
        d.setBizCategory(category);
        d.setStartAt(req.getStartAt());
        d.setEndAt(req.getEndAt());
        d.setStatus(ST_ACTIVE);
        d.setRemark(StringUtils.hasText(req.getRemark()) ? req.getRemark().trim() : null);
        d.setCreatedBy(me);
        delegationMapper.insert(d);

        log.info("新建审批委托 id={} 委托人={} 受托人={} 类别={} 期间={} ~ {}",
                d.getId(), user.getRealName(), delegate.getRealName(), category,
                req.getStartAt(), req.getEndAt());
        return toVO(d, Map.of(me, user.getRealName(), delegateId, delegate.getRealName()));
    }

    /** 撤销委托。**只有委托人本人能撤**（受托人不能替委托人结束委托） */
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long id, LoginUser user) {
        FlowDelegation d = delegationMapper.selectById(id);
        if (d == null) {
            throw BizException.notFound("委托不存在: " + id);
        }
        if (!Objects.equals(d.getDelegatorId(), user.getUserId())) {
            throw BizException.forbidden("只能撤销自己设置的委托");
        }
        if (Objects.equals(d.getStatus(), ST_CANCELLED)) {
            throw BizException.of("该委托已经是撤销状态");
        }
        delegationMapper.update(null, Wrappers.<FlowDelegation>lambdaUpdate()
                .eq(FlowDelegation::getId, id)
                .set(FlowDelegation::getStatus, ST_CANCELLED)
                .set(FlowDelegation::getUpdatedBy, user.getUserId()));
        log.info("撤销审批委托 id={} 操作人={}", id, user.getRealName());
    }

    /* ================================================================== 内部 */

    /**
     * 同一委托人 + 同一受托人 + 同一类别的时间段不允许重叠。
     *
     * <p>重叠本身不会造成越权，但会让"这条待办到底为什么在我这儿"变得难以回答 ——
     * 出问题时无从判断是哪条委托放进来的，所以宁可让人一次只设一条覆盖该时段的。
     */
    private void assertNoOverlap(Long delegatorId, Long delegateId, String category,
                                 LocalDateTime start, LocalDateTime end) {
        var w = Wrappers.<FlowDelegation>lambdaQuery()
                .eq(FlowDelegation::getDelegatorId, delegatorId)
                .eq(FlowDelegation::getDelegateId, delegateId)
                .eq(FlowDelegation::getStatus, ST_ACTIVE)
                .lt(FlowDelegation::getStartAt, end)      // 已有.start < 新.end
                .gt(FlowDelegation::getEndAt, start);     // 已有.end   > 新.start
        if (category == null) {
            w.isNull(FlowDelegation::getBizCategory);
        } else {
            w.eq(FlowDelegation::getBizCategory, category);
        }
        if (delegationMapper.selectCount(w) > 0) {
            throw BizException.of("该受托人在 %s ~ %s 已有同类别（%s）的生效委托，请先撤销或调整时间段",
                    start, end, category == null ? "全部" : category);
        }
    }

    private List<DelegationVO> toVOs(List<FlowDelegation> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        var ids = list.stream()
                .flatMap(d -> java.util.stream.Stream.of(d.getDelegatorId(), d.getDelegateId()))
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, String> names = userMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(SysUser::getId, SysUser::getRealName, (a, b) -> a));
        return list.stream().map(d -> toVO(d, names)).toList();
    }

    private DelegationVO toVO(FlowDelegation d, Map<Long, String> names) {
        DelegationVO vo = new DelegationVO();
        vo.setId(d.getId());
        vo.setDelegatorId(d.getDelegatorId());
        vo.setDelegatorName(names.get(d.getDelegatorId()));
        vo.setDelegateId(d.getDelegateId());
        vo.setDelegateName(names.get(d.getDelegateId()));
        vo.setBizCategory(d.getBizCategory());
        vo.setBizCategoryText(Objects.isNull(d.getBizCategory()) ? "全部单据" : d.getBizCategory());
        vo.setStartAt(d.getStartAt());
        vo.setEndAt(d.getEndAt());
        vo.setStatus(d.getStatus());
        vo.setRemark(d.getRemark());
        LocalDateTime now = LocalDateTime.now();
        vo.setActive(Objects.equals(d.getStatus(), ST_ACTIVE)
                && d.getStartAt() != null && d.getEndAt() != null
                && !now.isBefore(d.getStartAt()) && !now.isAfter(d.getEndAt()));
        vo.setCreatedAt(d.getCreatedAt());
        return vo;
    }
}
