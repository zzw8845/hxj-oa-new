package com.hxj.oa.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hxj.oa.common.exception.BizException;
import com.hxj.oa.common.security.UserContext;
import com.hxj.oa.system.dto.UserSaveReq;
import com.hxj.oa.system.dto.UserVO;
import com.hxj.oa.system.entity.Department;
import com.hxj.oa.system.entity.Post;
import com.hxj.oa.system.entity.SysRole;
import com.hxj.oa.system.entity.SysUser;
import com.hxj.oa.system.entity.UserPost;
import com.hxj.oa.system.entity.UserRole;
import com.hxj.oa.system.mapper.DepartmentMapper;
import com.hxj.oa.system.mapper.PostMapper;
import com.hxj.oa.system.mapper.SysRoleMapper;
import com.hxj.oa.system.mapper.SysUserMapper;
import com.hxj.oa.system.mapper.UserPostMapper;
import com.hxj.oa.system.mapper.UserRoleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 人员管理（写侧）：新建员工、编辑、调整角色、停用删除。
 *
 * <p>三条不变量，任何入口都不允许绕过：
 * <ol>
 *   <li><b>账号全局唯一</b>（uk_user_account 不含 company_id）；工号限本公司唯一。</li>
 *   <li><b>密码只进不出</b>：一律 BCrypt 哈希后落库，接口永不回显。</li>
 *   <li><b>角色绑定是「全量覆盖」语义</b>：提交什么就是什么，未提交的绑定会被清掉，
 *       避免「界面上取消了角色、库里的关联还在」这种最容易被忽略的越权残留。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAdminService {

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final UserPostMapper userPostMapper;
    private final DepartmentMapper deptMapper;
    private final PostMapper postMapper;
    private final OrgResolver orgResolver;
    private final PasswordEncoder passwordEncoder;

    /* ------------------------------------------------------------------ 查询 */

    /** 人员列表：一次性把部门名、岗位名、角色拼齐，避免 N+1 也避免前端逐行再查 */
    public List<UserVO> listWithDetail(Long companyId) {
        List<SysUser> users = userMapper.selectList(Wrappers.<SysUser>lambdaQuery()
                .eq(companyId != null, SysUser::getCompanyId, companyId)
                .orderByAsc(SysUser::getDeptId)
                .orderByAsc(SysUser::getId));
        return assembleAll(users, companyId);
    }

    /** 组装公共部分：批量取部门名/岗位名/角色，映射为 VO */
    private List<UserVO> assembleAll(List<SysUser> users, Long companyId) {
        if (users.isEmpty()) {
            return List.of();
        }
        Set<Long> deptIds = users.stream().map(SysUser::getDeptId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Long> postIds = users.stream().map(SysUser::getPostId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Long> userIds = users.stream().map(SysUser::getId).collect(Collectors.toSet());

        Map<Long, String> deptNames = deptNameMap(deptIds);
        Map<Long, String> postNames = postNameMap(postIds);
        Map<Long, List<String>> roleCodesByUser = roleCodesByUser(userIds);
        Map<String, String> roleNameByCode = roleNameByCode(companyId);

        return users.stream()
                .map(u -> assemble(u, deptNames, postNames, roleCodesByUser, roleNameByCode))
                .toList();
    }

    public UserVO detail(Long id) {
        SysUser u = requireUser(id);
        return assemble(u,
                deptNameMap(u.getDeptId() == null ? Set.of() : Set.of(u.getDeptId())),
                postNameMap(u.getPostId() == null ? Set.of() : Set.of(u.getPostId())),
                roleCodesByUser(Set.of(id)),
                roleNameByCode(u.getCompanyId()));
    }

    /* ------------------------------------------------------------------ 写 */

    @Transactional(rollbackFor = Exception.class)
    public UserVO create(UserSaveReq req) {
        Long companyId = UserContext.currentCompanyId();
        String account = req.getAccount().trim();
        String jobNo = req.getJobNo().trim();

        if (!StringUtils.hasText(req.getPassword())) {
            throw BizException.of("新建员工必须设置初始密码");
        }
        assertAccountFree(account, null);
        assertJobNoFree(companyId, jobNo, null);

        SysUser u = new SysUser();
        u.setCompanyId(companyId);
        u.setAccount(account);
        u.setJobNo(jobNo);
        u.setRealName(req.getRealName().trim());
        u.setPassword(passwordEncoder.encode(req.getPassword()));
        u.setPhone(emptyToNull(req.getPhone()));
        u.setEmail(emptyToNull(req.getEmail()));
        u.setDeptId(orgResolver.resolveDeptId(companyId, req.getDeptId(), req.getDeptName()));
        u.setPostId(orgResolver.resolvePostId(companyId, req.getPostId(), req.getPostName()));
        u.setStatus(req.getStatus() == null ? 1 : req.getStatus());
        // 管理员设的是「初始密码」，标记为待改密；当前登录流程不强制跳转，仅作留痕
        u.setPwdResetFlag(1);
        u.setCreatedBy(UserContext.currentUserId());
        userMapper.insert(u);

        rebindRoles(u.getId(), orgResolver.resolveRoleCodes(companyId, req.getRoleCodes(), req.getRoleNames()));
        rebindPrimaryPost(u);

        log.info("新建员工 id={} account={} deptId={} postId={} 操作人={}",
                u.getId(), account, u.getDeptId(), u.getPostId(), UserContext.currentUserId());
        return detail(u.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public UserVO update(Long id, UserSaveReq req) {
        SysUser exist = requireUser(id);
        Long companyId = exist.getCompanyId();
        String account = req.getAccount().trim();
        String jobNo = req.getJobNo().trim();

        assertAccountFree(account, id);
        assertJobNoFree(companyId, jobNo, id);

        SysUser upd = new SysUser();
        upd.setId(id);
        upd.setAccount(account);
        upd.setJobNo(jobNo);
        upd.setRealName(req.getRealName().trim());
        upd.setPhone(emptyToNull(req.getPhone()));
        upd.setEmail(emptyToNull(req.getEmail()));
        upd.setDeptId(orgResolver.resolveDeptId(companyId, req.getDeptId(), req.getDeptName()));
        upd.setPostId(orgResolver.resolvePostId(companyId, req.getPostId(), req.getPostName()));
        upd.setStatus(req.getStatus() == null ? exist.getStatus() : req.getStatus());
        if (StringUtils.hasText(req.getPassword())) {
            upd.setPassword(passwordEncoder.encode(req.getPassword()));
            upd.setPwdResetFlag(1);
        }
        upd.setUpdatedBy(UserContext.currentUserId());
        userMapper.updateById(upd);

        // 角色/岗位：只有显式带了对应字段才覆盖，避免「只想改手机号」把角色清空
        if (req.getRoleCodes() != null || req.getRoleNames() != null) {
            rebindRoles(id, orgResolver.resolveRoleCodes(companyId, req.getRoleCodes(), req.getRoleNames()));
        }
        rebindPrimaryPost(userMapper.selectById(id));

        log.info("编辑员工 id={} account={} 操作人={}", id, account, UserContext.currentUserId());
        return detail(id);
    }

    /** 调整角色（界面上的「调整角色」按钮） */
    @Transactional(rollbackFor = Exception.class)
    public UserVO updateRoles(Long id, List<String> roleCodes) {
        SysUser exist = requireUser(id);
        List<String> resolved = orgResolver.resolveRoleCodes(exist.getCompanyId(), roleCodes, null);
        rebindRoles(id, resolved);
        log.info("调整员工角色 id={} roles={} 操作人={}", id, resolved, UserContext.currentUserId());
        return detail(id);
    }

    /**
     * 停用并删除员工。
     * 先置 status=0 再逻辑删除：即使将来有人用「包含已删除」的口径查库，
     * 也不会把离职人员当成在职人员派发审批任务。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        SysUser exist = requireUser(id);
        if (Objects.equals(id, UserContext.currentUserId())) {
            throw BizException.of("不能删除当前登录账号");
        }
        if (exist.getStatus() != null && exist.getStatus() == 0) {
            throw BizException.of("员工「%s」已停用", exist.getRealName());
        }
        SysUser upd = new SysUser();
        upd.setId(id);
        upd.setStatus(0);
        // 释放账号与工号，而不是原样保留：
        // uk_user_account(account, deleted) 与 uk_user_company_jobno 把 deleted 也纳入了唯一键，
        // 而 deleted=1 的记录只能存在一条。若不解绑，「同一账号 创建→删除→再创建→再删除」
        // 会在第二次删除时撞唯一键，前端看到的是 500。改名后既让位给将来的记录，又保留审计痕迹。
        upd.setAccount(UniqueKeys.release(exist.getAccount(), id, 64));
        upd.setJobNo(UniqueKeys.release(exist.getJobNo(), id, 32));
        upd.setUpdatedBy(UserContext.currentUserId());
        userMapper.updateById(upd);

        userMapper.deleteById(id);
        // 角色绑定必须真删：留着的话，账号被重建（同 id 复用场景）会意外继承旧权限
        userRoleMapper.physicalDeleteByUserId(id);
        userPostMapper.physicalDeleteByUserId(id);
        log.info("删除员工 id={} account={} 操作人={}", id, exist.getAccount(), UserContext.currentUserId());
    }

    /* ------------------------------------------------------------------ 内部 */

    private SysUser requireUser(Long id) {
        SysUser u = userMapper.selectById(id);
        if (u == null) {
            throw BizException.notFound("员工不存在: " + id);
        }
        return u;
    }

    private void assertAccountFree(String account, Long excludeId) {
        long n = userMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getAccount, account)
                .ne(excludeId != null, SysUser::getId, excludeId));
        if (n > 0) {
            throw BizException.of("登录账号「%s」已被占用", account);
        }
    }

    private void assertJobNoFree(Long companyId, String jobNo, Long excludeId) {
        long n = userMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                .eq(companyId != null, SysUser::getCompanyId, companyId)
                .eq(SysUser::getJobNo, jobNo)
                .ne(excludeId != null, SysUser::getId, excludeId));
        if (n > 0) {
            throw BizException.of("工号「%s」在本公司已存在", jobNo);
        }
    }

    /** 全量覆盖角色绑定 */
    private void rebindRoles(Long userId, List<String> roleCodes) {
        userRoleMapper.physicalDeleteByUserId(userId);
        if (roleCodes == null || roleCodes.isEmpty()) {
            return;
        }
        // 按提交顺序写入，而不是按角色表 id 排序：
        // 界面上回显的"分配角色"顺序应与用户勾选的顺序一致，否则每次保存看起来都被重排了一遍。
        Map<String, SysRole> byCode = roleMapper.selectList(Wrappers.<SysRole>lambdaQuery()
                        .in(SysRole::getCode, roleCodes))
                .stream().collect(Collectors.toMap(SysRole::getCode, r -> r, (a, b) -> a));
        Long operator = UserContext.currentUserId();
        for (String code : new LinkedHashSet<>(roleCodes)) {
            SysRole r = byCode.get(code);
            if (r == null) {
                continue;
            }
            UserRole link = new UserRole();
            link.setUserId(userId);
            link.setRoleId(r.getId());
            link.setCreatedBy(operator);
            userRoleMapper.insert(link);
        }
    }

    /** 维护主岗记录（user_post），供「按岗位找人」的指派规则使用 */
    private void rebindPrimaryPost(SysUser u) {
        if (u == null) {
            return;
        }
        userPostMapper.physicalDeleteByUserId(u.getId());
        if (u.getPostId() == null || u.getDeptId() == null) {
            return;
        }
        UserPost up = new UserPost();
        up.setUserId(u.getId());
        up.setPostId(u.getPostId());
        up.setDeptId(u.getDeptId());
        up.setIsPrimary(1);
        up.setCreatedBy(UserContext.currentUserId());
        userPostMapper.insert(up);
    }

    private UserVO assemble(SysUser u,
                            Map<Long, String> deptNames,
                            Map<Long, String> postNames,
                            Map<Long, List<String>> roleCodesByUser,
                            Map<String, String> roleNameByCode) {
        UserVO vo = new UserVO();
        BeanUtils.copyProperties(u, vo);
        // 必须先判空再查表：xxxNameMap 在没有 id 时返回 Map.of()（不可变空 Map），
        // 而不可变 Map 的 get(null) 会抛 NPE —— 岗位/部门是可空字段，
        // 「新建员工时不填岗位」就会走到这里，表现为 500 而不是把岗位留空。
        vo.setDeptName(u.getDeptId() == null ? null : deptNames.get(u.getDeptId()));
        vo.setPostName(u.getPostId() == null ? null : postNames.get(u.getPostId()));
        List<String> codes = roleCodesByUser.getOrDefault(u.getId(), List.of());
        vo.setRoleCodes(codes);
        vo.setRoleNames(codes.stream()
                .map(c -> roleNameByCode.getOrDefault(c, c))
                .toList());
        return vo;
    }

    private Map<Long, String> deptNameMap(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return deptMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Department::getId, Department::getName, (a, b) -> a));
    }

    private Map<Long, String> postNameMap(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return postMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Post::getId, Post::getName, (a, b) -> a));
    }

    /** 批量取 用户ID → 角色编码列表 */
    private Map<Long, List<String>> roleCodesByUser(Set<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        List<UserRole> links = userRoleMapper.selectList(Wrappers.<UserRole>lambdaQuery()
                .in(UserRole::getUserId, userIds));
        if (links.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> codeById = roleMapper
                .selectBatchIds(links.stream().map(UserRole::getRoleId).distinct().toList())
                .stream().collect(Collectors.toMap(SysRole::getId, SysRole::getCode, (a, b) -> a));
        Map<Long, List<String>> out = new LinkedHashMap<>();
        for (UserRole l : links) {
            String code = codeById.get(l.getRoleId());
            if (code != null) {
                out.computeIfAbsent(l.getUserId(), k -> new java.util.ArrayList<>()).add(code);
            }
        }
        return out;
    }

    private Map<String, String> roleNameByCode(Long companyId) {
        List<SysRole> roles = roleMapper.selectList(Wrappers.<SysRole>lambdaQuery()
                .eq(companyId != null, SysRole::getCompanyId, companyId));
        if (roles.isEmpty()) {
            return Collections.emptyMap();
        }
        return roles.stream().collect(Collectors.toMap(SysRole::getCode, SysRole::getName, (a, b) -> a));
    }

    private String emptyToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }
}
