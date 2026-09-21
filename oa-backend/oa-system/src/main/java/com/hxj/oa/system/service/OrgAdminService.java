package com.hxj.oa.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hxj.oa.common.exception.BizException;
import com.hxj.oa.common.security.UserContext;
import com.hxj.oa.system.auth.AuthSnapshotCache;
import com.hxj.oa.system.dto.DeptSaveReq;
import com.hxj.oa.system.dto.DictSaveReq;
import com.hxj.oa.system.dto.PostSaveReq;
import com.hxj.oa.system.entity.Department;
import com.hxj.oa.system.entity.Post;
import com.hxj.oa.system.entity.SysDict;
import com.hxj.oa.system.entity.SysUser;
import com.hxj.oa.system.entity.UserPost;
import com.hxj.oa.system.mapper.DepartmentMapper;
import com.hxj.oa.system.mapper.PostMapper;
import com.hxj.oa.system.mapper.SysDictMapper;
import com.hxj.oa.system.mapper.SysUserMapper;
import com.hxj.oa.system.mapper.UserPostMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 主数据管理（写侧）：部门 / 岗位 / 字典的增改删。
 *
 * <p>与读侧分开（{@link DepartmentService} / {@link DictService}）是照 {@code UserAdminService}、
 * {@code RoleAdminService} 的既有分工：读接口对全体登录用户开放（发起单据要选部门、前端要预热字典），
 * 写接口一律挂权限点。混在一个类里，很容易在后续改动中把写方法顺手暴露给读路径。
 *
 * <h3>本类承担的不变量（每一条都是"错了还看不出来"的那种）</h3>
 *
 * <p><b>1. 部门物化路径必须自包含且正确。</b>
 * {@code department.path} 形如 {@code /2/3/}（自包含：含自己），
 * 行级数据权限用的 {@code LIKE '/2/%'} 就建立在它上面。插入时还不知道自增 ID，
 * 所以做法是「先插拿 ID，同一事务内回填 path」—— 拆成两个事务的话，
 * 中间那一刻的部门没有合法 path，若有并发请求按 path 过滤就会漏掉它。
 *
 * <p><b>2. 部门移动刻意不支持。</b>移动要重写整棵子树的 path，而我们没有批量保证；
 * 做一半的移动比不做更糟（部分子孙 path 失效 → 只有部分人能看见这些单据）。
 * 所以 {@code parentId} 不可改，并在报错里说明这是刻意的，避免被当成 bug 上报。
 *
 * <p><b>3. 删除前必须过引用守卫。</b>部门被员工/兼岗/子部门引用、岗位被用户引用时删除，
 * 库里不会报错（逻辑删除不触发外键），但会留下指向已删记录的引用，
 * 表现为"某人的部门显示空白""审批人解析不出来"，且很久之后才会被发现。
 *
 * <p><b>4. 逻辑删除前要让唯一键让位。</b>{@code uk_dept_company_code} / {@code uk_post_company_code}
 * 都把 {@code deleted} 放进了键里，于是 deleted=1 的记录只能存在一条 ⇒
 * "建→删→再建→再删"的第二次删除会撞键报 500。统一走 {@link UniqueKeys#release}。
 *
 * <p><b>5. 部门变更必须让权限快照失效。</b>{@code LoginUser.deptPath} 会编进 JWT，
 * 而它是行级权限的判据之一。改了部门树而快照不失效，用户手里的旧令牌会继续按旧 path
 * 过滤数据 —— 这一条尤其阴险：所有单元测试都会通过，只有真人操作才会发现"看不到新部门的单据"。
 *
 * <p><b>6. 字典的唯一性必须自己查，不能指望索引。</b>
 * {@code uk_dict(dict_type, dict_code, company_id, deleted)} 里 {@code company_id} 允许为 NULL，
 * 而 MySQL 唯一索引认为**多个 NULL 互不相同**；本项目的字典恰好全是全局字典（company_id 为 NULL）。
 * 所以同一个 dict_code 可以重复插进去而索引不拦。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrgAdminService {

    private final DepartmentMapper deptMapper;
    private final PostMapper postMapper;
    private final SysDictMapper dictMapper;
    private final SysUserMapper userMapper;
    private final UserPostMapper userPostMapper;
    private final AuthSnapshotCache snapshotCache;

    /* ==================================================================== 部门 */

    /**
     * 新建部门。{@code deptType} 与 {@code level} 一律由父节点推导，不接受调用方指定 ——
     * 让调用方填必然会填错（它与 parentId 是同一条信息的两种表达），
     * 而错了之后表现为树渲染错乱，根因很难追。
     */
    @Transactional(rollbackFor = Exception.class)
    public Department createDept(DeptSaveReq req) {
        Long companyId = UserContext.currentCompanyId();
        String name = requireText(req.getName(), "部门名称");
        String code = StringUtils.hasText(req.getCode()) ? req.getCode().trim() : nextDeptCode(companyId);
        assertDeptCodeFree(companyId, code, null);

        long parentId = req.getParentId() == null ? 0L : req.getParentId();
        Department parent = null;
        if (parentId != 0L) {
            parent = requireDept(parentId, companyId);
        }
        assertLeaderOk(companyId, req.getLeaderId());

        Department d = new Department();
        d.setCompanyId(companyId);
        d.setParentId(parentId);
        d.setCode(code);
        d.setName(name);
        // 一级中心(1) / 二级部门(2)：由"有没有上级"决定，不由调用方决定
        d.setDeptType(parent == null ? 1 : 2);
        d.setLevel(parent == null ? 1 : parent.getLevel() + 1);
        d.setPath("");                       // 见下方回填
        d.setLeaderId(req.getLeaderId());
        d.setStatus(req.getStatus() == null ? 1 : req.getStatus());
        d.setSortNo(req.getSortNo() == null ? 0 : req.getSortNo());
        d.setCreatedBy(UserContext.currentUserId());
        deptMapper.insert(d);

        // 回填物化路径：父路径本身已带尾斜杠（/2/），拼上自己的 ID 再补一个尾斜杠
        String path = (parent == null ? "/" : parent.getPath()) + d.getId() + "/";
        Department upd = new Department();
        upd.setId(d.getId());
        upd.setPath(path);
        upd.setUpdatedBy(UserContext.currentUserId());
        deptMapper.updateById(upd);
        d.setPath(path);

        // deptPath 进了 JWT，改了树就必须让快照失效（见类注释第 5 条）
        snapshotCache.invalidateAfterCommit();
        log.info("新建部门 id={} code={} name={} parentId={} path={} 操作人={}",
                d.getId(), code, name, parentId, path, UserContext.currentUserId());
        return d;
    }

    /** 编辑部门：改名 / 编码 / 负责人 / 排序 / 状态。不支持改上级（见类注释第 2 条）。 */
    @Transactional(rollbackFor = Exception.class)
    public Department updateDept(Long id, DeptSaveReq req) {
        Department exist = requireDept(id, null);

        if (req.getParentId() != null && !req.getParentId().equals(exist.getParentId())) {
            throw BizException.of("本系统不支持调整部门的上级（%s → %s）："
                            + "移动需要重写整棵子树的物化路径，做一半会让部分子孙的路径失效，"
                            + "反而比不动更危险。如需调整组织架构，请新建部门后迁移人员。",
                    exist.getParentId(), req.getParentId());
        }

        Department upd = new Department();
        upd.setId(id);
        if (StringUtils.hasText(req.getName())) {
            upd.setName(req.getName().trim());
        }
        if (StringUtils.hasText(req.getCode())) {
            String code = req.getCode().trim();
            if (!code.equals(exist.getCode())) {
                assertDeptCodeFree(exist.getCompanyId(), code, id);
                upd.setCode(code);
            }
        }
        if (req.getLeaderId() != null) {
            assertLeaderOk(exist.getCompanyId(), req.getLeaderId());
            // 显式支持"清空负责人"：传 0 表示置空。
            // MyBatis-Plus 的 updateById 会忽略 null 字段，所以不能用"传 null 即清空"，
            // 那会让"想清空"变成"什么都没改"，而且不报错。
            upd.setLeaderId(req.getLeaderId() == 0L ? null : req.getLeaderId());
        }
        if (req.getSortNo() != null) {
            upd.setSortNo(req.getSortNo());
        }
        if (req.getStatus() != null) {
            upd.setStatus(req.getStatus());
        }
        upd.setUpdatedBy(UserContext.currentUserId());
        deptMapper.updateById(upd);

        snapshotCache.invalidateAfterCommit();
        log.info("编辑部门 id={} 操作人={}", id, UserContext.currentUserId());
        return deptMapper.selectById(id);
    }

    /** 删除部门（逻辑删除）。三道引用守卫，任一不过都拒绝。 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteDept(Long id) {
        Department d = requireDept(id, null);

        long children = deptMapper.selectCount(Wrappers.<Department>lambdaQuery()
                .eq(Department::getParentId, id));
        if (children > 0) {
            throw BizException.of("部门「%s」下还有 %d 个子部门，请先处理子部门", d.getName(), children);
        }

        List<SysUser> members = userMapper.selectList(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getDeptId, id));
        if (!members.isEmpty()) {
            long active = members.stream().filter(u -> u.getStatus() != null && u.getStatus() == 1).count();
            throw BizException.of("部门「%s」下还有 %d 名员工（其中在职 %d 名），请先把人员调到其他部门",
                    d.getName(), members.size(), active);
        }

        long postBindings = userPostMapper.selectCount(Wrappers.<UserPost>lambdaQuery()
                .eq(UserPost::getDeptId, id));
        if (postBindings > 0) {
            throw BizException.of("部门「%s」上还挂着 %d 条兼岗记录，请先解除", d.getName(), postBindings);
        }

        // 让编码让位给将来同编码的部门（见类注释第 4 条）
        Department upd = new Department();
        upd.setId(id);
        upd.setCode(UniqueKeys.release(d.getCode(), id, 64));
        upd.setUpdatedBy(UserContext.currentUserId());
        deptMapper.updateById(upd);
        deptMapper.deleteById(id);

        snapshotCache.invalidateAfterCommit();
        log.info("删除部门 id={} code={} 操作人={}", id, d.getCode(), UserContext.currentUserId());
    }

    /* ==================================================================== 岗位 */

    @Transactional(rollbackFor = Exception.class)
    public Post createPost(PostSaveReq req) {
        Long companyId = UserContext.currentCompanyId();
        String name = requireText(req.getName(), "岗位名称");
        String code = StringUtils.hasText(req.getCode()) ? req.getCode().trim() : nextPostCode(companyId);
        assertPostCodeFree(companyId, code, null);

        Post p = new Post();
        p.setCompanyId(companyId);
        p.setCode(code);
        p.setName(name);
        p.setStatus(req.getStatus() == null ? 1 : req.getStatus());
        p.setSortNo(req.getSortNo() == null ? 0 : req.getSortNo());
        p.setCreatedBy(UserContext.currentUserId());
        postMapper.insert(p);

        log.info("新建岗位 id={} code={} name={} 操作人={}",
                p.getId(), code, name, UserContext.currentUserId());
        return p;
    }

    @Transactional(rollbackFor = Exception.class)
    public Post updatePost(Long id, PostSaveReq req) {
        Post exist = requirePost(id);
        Post upd = new Post();
        upd.setId(id);
        if (StringUtils.hasText(req.getName())) {
            upd.setName(req.getName().trim());
        }
        if (StringUtils.hasText(req.getCode())) {
            String code = req.getCode().trim();
            if (!code.equals(exist.getCode())) {
                assertPostCodeFree(exist.getCompanyId(), code, id);
                upd.setCode(code);
            }
        }
        if (req.getSortNo() != null) {
            upd.setSortNo(req.getSortNo());
        }
        if (req.getStatus() != null) {
            upd.setStatus(req.getStatus());
        }
        upd.setUpdatedBy(UserContext.currentUserId());
        postMapper.updateById(upd);
        log.info("编辑岗位 id={} 操作人={}", id, UserContext.currentUserId());
        return postMapper.selectById(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deletePost(Long id) {
        Post p = requirePost(id);

        long holders = userMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getPostId, id));
        if (holders > 0) {
            throw BizException.of("岗位「%s」还有 %d 名员工在任，请先调整这些员工的岗位", p.getName(), holders);
        }
        long bindings = userPostMapper.selectCount(Wrappers.<UserPost>lambdaQuery()
                .eq(UserPost::getPostId, id));
        if (bindings > 0) {
            throw BizException.of("岗位「%s」还挂在 %d 条兼岗记录上，请先解除", p.getName(), bindings);
        }

        Post upd = new Post();
        upd.setId(id);
        upd.setCode(UniqueKeys.release(p.getCode(), id, 64));
        upd.setUpdatedBy(UserContext.currentUserId());
        postMapper.updateById(upd);
        postMapper.deleteById(id);
        log.info("删除岗位 id={} code={} 操作人={}", id, p.getCode(), UserContext.currentUserId());
    }

    /* ==================================================================== 字典 */

    /**
     * 新建字典项。companyId 固定为 NULL（全局字典）—— 与库中既有 17 条保持一致，
     * 也与 {@link DictService} 的读实现一致（它压根不按 company 过滤）。
     * 若将来要做多公司字典，读侧必须同步加过滤，否则"某公司的字典项会泄漏给其他公司"。
     */
    @Transactional(rollbackFor = Exception.class)
    public SysDict createDict(DictSaveReq req) {
        String type = requireText(req.getDictType(), "字典类型");
        String code = requireText(req.getDictCode(), "字典项编码");
        String label = requireText(req.getDictLabel(), "字典项名称");
        // 唯一性自己查（见类注释第 6 条）
        assertDictFree(type, code, null);

        SysDict d = new SysDict();
        d.setCompanyId(null);
        d.setDictType(type);
        d.setDictCode(code);
        d.setDictLabel(label);
        d.setStatus(req.getStatus() == null ? 1 : req.getStatus());
        d.setSortNo(req.getSortNo() == null ? 0 : req.getSortNo());
        dictMapper.insert(d);
        log.info("新建字典项 id={} type={} code={} label={} 操作人={}",
                d.getId(), type, code, label, UserContext.currentUserId());
        return d;
    }

    /** 编辑字典项。{@code dictType} 与 {@code dictCode} 一起构成唯一键，改动前要重新查重。 */
    @Transactional(rollbackFor = Exception.class)
    public SysDict updateDict(Long id, DictSaveReq req) {
        SysDict exist = requireDict(id);

        SysDict upd = new SysDict();
        upd.setId(id);

        String type = StringUtils.hasText(req.getDictType()) ? req.getDictType().trim() : exist.getDictType();
        String code = StringUtils.hasText(req.getDictCode()) ? req.getDictCode().trim() : exist.getDictCode();
        if (!type.equals(exist.getDictType()) || !code.equals(exist.getDictCode())) {
            assertDictFree(type, code, id);
            upd.setDictType(type);
            upd.setDictCode(code);
        }
        if (StringUtils.hasText(req.getDictLabel())) {
            upd.setDictLabel(req.getDictLabel().trim());
        }
        if (req.getSortNo() != null) {
            upd.setSortNo(req.getSortNo());
        }
        if (req.getStatus() != null) {
            upd.setStatus(req.getStatus());
        }
        dictMapper.updateById(upd);
        log.info("编辑字典项 id={} type={} code={} 操作人={}", id, type, code, UserContext.currentUserId());
        return dictMapper.selectById(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteDict(Long id) {
        SysDict d = requireDict(id);
        // 字典项没有引用关系（前端按 dictType 取列表渲染），所以不需要引用守卫；
        // 但仍要让唯一键让位，否则"建→删→再建→再删"第二次删除会撞键。
        SysDict upd = new SysDict();
        upd.setId(id);
        upd.setDictCode(UniqueKeys.release(d.getDictCode(), id, 64));
        dictMapper.updateById(upd);
        dictMapper.deleteById(id);
        log.info("删除字典项 id={} type={} code={} 操作人={}",
                id, d.getDictType(), d.getDictCode(), UserContext.currentUserId());
    }

    /* ==================================================================== 内部 */

    private String requireText(String v, String what) {
        if (!StringUtils.hasText(v)) {
            throw BizException.of("%s不能为空", what);
        }
        return v.trim();
    }

    private Department requireDept(Long id, Long companyId) {
        Department d = deptMapper.selectById(id);
        if (d == null) {
            throw BizException.notFound("部门不存在: " + id);
        }
        if (companyId != null && !companyId.equals(d.getCompanyId())) {
            // 显式挡住跨公司操作：不校验的话，A 公司管理员传 B 公司的部门 ID 就能改到别人头上
            throw BizException.forbidden("部门不属于当前公司: " + id);
        }
        return d;
    }

    private Post requirePost(Long id) {
        Post p = postMapper.selectById(id);
        if (p == null) {
            throw BizException.notFound("岗位不存在: " + id);
        }
        return p;
    }

    private SysDict requireDict(Long id) {
        SysDict d = dictMapper.selectById(id);
        if (d == null) {
            throw BizException.notFound("字典项不存在: " + id);
        }
        return d;
    }

    private void assertLeaderOk(Long companyId, Long leaderId) {
        if (leaderId == null || leaderId == 0L) {
            return;
        }
        SysUser u = userMapper.selectById(leaderId);
        if (u == null || (companyId != null && !companyId.equals(u.getCompanyId()))) {
            throw BizException.of("指定的负责人不存在或不属于本公司: %s", leaderId);
        }
    }

    private void assertDeptCodeFree(Long companyId, String code, Long excludeId) {
        long n = deptMapper.selectCount(Wrappers.<Department>lambdaQuery()
                .eq(companyId != null, Department::getCompanyId, companyId)
                .eq(Department::getCode, code)
                .ne(excludeId != null, Department::getId, excludeId));
        if (n > 0) {
            throw BizException.of("部门编码「%s」已存在", code);
        }
    }

    private void assertPostCodeFree(Long companyId, String code, Long excludeId) {
        long n = postMapper.selectCount(Wrappers.<Post>lambdaQuery()
                .eq(companyId != null, Post::getCompanyId, companyId)
                .eq(Post::getCode, code)
                .ne(excludeId != null, Post::getId, excludeId));
        if (n > 0) {
            throw BizException.of("岗位编码「%s」已存在", code);
        }
    }

    /**
     * 字典查重。{@code IS NULL} 必须单独写 —— {@code eq(column, null)} 在 MyBatis-Plus 里
     * 会被忽略（等于没加条件），那样查重就永远查不到任何东西，反而"放行重复"。
     */
    private void assertDictFree(String type, String code, Long excludeId) {
        long n = dictMapper.selectCount(Wrappers.<SysDict>lambdaQuery()
                .eq(SysDict::getDictType, type)
                .eq(SysDict::getDictCode, code)
                .isNull(SysDict::getCompanyId)
                .ne(excludeId != null, SysDict::getId, excludeId));
        if (n > 0) {
            throw BizException.of("字典项「%s / %s」已存在", type, code);
        }
    }

    private String nextDeptCode(Long companyId) {
        for (int i = 1; i <= 999; i++) {
            String code = String.format("D%03d", i);
            boolean used = deptMapper.selectCount(Wrappers.<Department>lambdaQuery()
                    .eq(companyId != null, Department::getCompanyId, companyId)
                    .eq(Department::getCode, code)) > 0;
            if (!used) {
                return code;
            }
        }
        throw BizException.of("部门编码已用尽（D001 ~ D999）");
    }

    private String nextPostCode(Long companyId) {
        for (int i = 1; i <= 999; i++) {
            String code = String.format("P%03d", i);
            boolean used = postMapper.selectCount(Wrappers.<Post>lambdaQuery()
                    .eq(companyId != null, Post::getCompanyId, companyId)
                    .eq(Post::getCode, code)) > 0;
            if (!used) {
                return code;
            }
        }
        throw BizException.of("岗位编码已用尽（P001 ~ P999）");
    }
}
