package com.hxj.oa.system.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hxj.oa.common.annotation.Audit;
import com.hxj.oa.common.api.R;
import com.hxj.oa.common.security.RequirePerm;
import com.hxj.oa.common.security.UserContext;
import com.hxj.oa.system.dto.PostSaveReq;
import com.hxj.oa.system.entity.Post;
import com.hxj.oa.system.mapper.PostMapper;
import com.hxj.oa.system.service.OrgAdminService;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 岗位管理。
 *
 * <p><b>读接口对全体登录用户开放</b>：人员管理页要把岗位渲染成下拉框
 * （在此之前前端是自由文本输入框，员工岗位靠人手工打字，同一个岗位能被写成好几种说法）。
 * 与部门、字典的读接口口径保持一致。
 *
 * <p><b>写接口挂 {@code system:dept}</b>：部门与岗位都属于"组织架构"，共用同一个权限点。
 * 刻意不单设 {@code system:post} —— 权限目录里每多一个点，配角色时就要多勾一次，
 * 而组织架构的维护者天然是同一批人。
 *
 * <p>写接口必须同时满足三件事，缺一不可（与 {@link DepartmentController} 的约定一致）：
 * 挂权限点、补 {@code @Audit}、有接口级用例钉住越权与唯一性。
 */
@RestController
@RequestMapping("/api/posts")
@RequiredArgsConstructor
public class PostController {

    private final PostMapper postMapper;
    private final OrgAdminService orgAdminService;

    /**
     * 岗位列表（人员管理页与「选岗位」下拉的数据源）。
     *
     * @param companyId 公司 ID；不传则取当前登录人的公司
     */
    @GetMapping
    public R<List<Post>> list(@RequestParam(required = false) Long companyId) {
        Long cid = companyId == null ? UserContext.require().getCompanyId() : companyId;
        return R.ok(postMapper.selectList(Wrappers.<Post>lambdaQuery()
                .eq(cid != null, Post::getCompanyId, cid)
                .orderByAsc(Post::getSortNo)
                .orderByAsc(Post::getId)));
    }

    /** 新建岗位。编码在同一公司内不允许重复。 */
    @PostMapping
    @RequirePerm("system:dept")
    @Audit(module = "system", action = "createPost")
    public R<Post> create(@Valid @RequestBody PostSaveReq req) {
        return R.ok(orgAdminService.createPost(req));
    }

    /**
     * 修改岗位。
     *
     * @param id 岗位 ID
     */
    @PutMapping("/{id}")
    @RequirePerm("system:dept")
    @Audit(module = "system", action = "updatePost")
    public R<Post> update(@PathVariable Long id, @Valid @RequestBody PostSaveReq req) {
        return R.ok(orgAdminService.updatePost(id, req));
    }

    /**
     * 删除岗位（逻辑删除）。
     *
     * <p>仍有员工在任、或仍挂在兼岗记录上时拒绝，并在 msg 里给出人数。
     *
     * @param id 岗位 ID
     */
    @DeleteMapping("/{id}")
    @RequirePerm("system:dept")
    @Audit(module = "system", action = "deletePost")
    public R<Void> delete(@PathVariable Long id) {
        orgAdminService.deletePost(id);
        return R.ok(null, "已删除");
    }
}
