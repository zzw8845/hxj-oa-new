package com.hxj.oa.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hxj.oa.common.exception.BizException;
import com.hxj.oa.system.entity.Department;
import com.hxj.oa.system.entity.Post;
import com.hxj.oa.system.entity.SysRole;
import com.hxj.oa.system.mapper.DepartmentMapper;
import com.hxj.oa.system.mapper.PostMapper;
import com.hxj.oa.system.mapper.SysRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 组织归属解析：把前端传来的「中文名称」翻译成库里的 ID / 编码。
 *
 * <p>存在的原因：原型前端的下拉框绑定的是**名称**（「综合管理中心」「核算会计」），
 * 而库表里存的是 ID 和稳定英文编码。与其在前端铺一层映射（还要处理重名、改名），
 * 不如在服务端统一收口：<b>传了 ID 就用 ID，只给名称就按「公司 + 名称」反查</b>。
 * 查不到一律抛业务异常并带上人话提示，而不是静默写成 null。
 */
@Component
@RequiredArgsConstructor
public class OrgResolver {

    private final DepartmentMapper deptMapper;
    private final PostMapper postMapper;
    private final SysRoleMapper roleMapper;

    /** @return null 表示未指定部门（允许，如总部直属人员） */
    public Long resolveDeptId(Long companyId, Long deptId, String deptName) {
        if (deptId != null) {
            Department d = deptMapper.selectById(deptId);
            if (d == null) {
                throw BizException.of("所选部门不存在（id=%s）", deptId);
            }
            return d.getId();
        }
        if (StringUtils.hasText(deptName)) {
            Department d = deptMapper.selectOne(Wrappers.<Department>lambdaQuery()
                    .eq(companyId != null, Department::getCompanyId, companyId)
                    .eq(Department::getName, deptName.trim())
                    .last("LIMIT 1"));
            if (d == null) {
                throw BizException.of("部门「%s」不存在，请先在部门管理中维护", deptName.trim());
            }
            return d.getId();
        }
        return null;
    }

    /**
     * 解析岗位。岗位是「可自由输入」的字段（原型里就是 allow-create 的下拉），
     * 因此名称对不上时不报错，而是自动补一条岗位档案——否则用户在界面上填个新岗位就存不了。
     *
     * @return null 表示未指定岗位
     */
    public Long resolvePostId(Long companyId, Long postId, String postName) {
        if (postId != null) {
            Post p = postMapper.selectById(postId);
            if (p == null) {
                throw BizException.of("所选岗位不存在（id=%s）", postId);
            }
            return p.getId();
        }
        if (!StringUtils.hasText(postName)) {
            return null;
        }
        String name = postName.trim();
        Post exist = postMapper.selectOne(Wrappers.<Post>lambdaQuery()
                .eq(companyId != null, Post::getCompanyId, companyId)
                .eq(Post::getName, name)
                .last("LIMIT 1"));
        if (exist != null) {
            return exist.getId();
        }
        Post p = new Post();
        p.setCompanyId(companyId);
        p.setName(name);
        p.setCode(nextPostCode(companyId));
        p.setStatus(1);
        p.setSortNo(0);
        postMapper.insert(p);
        return p.getId();
    }

    /**
     * 解析角色编码集合。roleCodes 优先；roleNames 按名称反查。
     * 角色必须已存在——这里不做自动创建，因为角色编码是流程指派规则的锚点（如 ACCOUNTANT），
     * 自动生成的编码没有业务含义，贸然创建会埋雷。
     */
    public List<String> resolveRoleCodes(Long companyId, List<String> roleCodes, List<String> roleNames) {
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        if (roleCodes != null) {
            for (String c : roleCodes) {
                if (!StringUtils.hasText(c)) {
                    continue;
                }
                String code = c.trim();
                if (roleMapper.selectCount(Wrappers.<SysRole>lambdaQuery()
                        .eq(companyId != null, SysRole::getCompanyId, companyId)
                        .eq(SysRole::getCode, code)) == 0) {
                    throw BizException.of("角色编码「%s」不存在", code);
                }
                codes.add(code);
            }
        }
        if (roleNames != null) {
            for (String n : roleNames) {
                if (!StringUtils.hasText(n)) {
                    continue;
                }
                String name = n.trim();
                SysRole r = roleMapper.selectOne(Wrappers.<SysRole>lambdaQuery()
                        .eq(companyId != null, SysRole::getCompanyId, companyId)
                        .eq(SysRole::getName, name)
                        .last("LIMIT 1"));
                if (r == null) {
                    throw BizException.of("角色「%s」不存在，请先在角色管理中创建", name);
                }
                codes.add(r.getCode());
            }
        }
        return new ArrayList<>(codes);
    }

    /** 生成不冲突的岗位编码：POST_001、POST_002 … */
    private String nextPostCode(Long companyId) {
        for (int i = 1; i <= 999; i++) {
            String code = String.format("POST_%03d", i);
            boolean used = postMapper.selectCount(Wrappers.<Post>lambdaQuery()
                    .eq(companyId != null, Post::getCompanyId, companyId)
                    .eq(Post::getCode, code)) > 0;
            if (!used) {
                return code;
            }
        }
        throw BizException.of("岗位编码已用尽（POST_001 ~ POST_999）");
    }
}
