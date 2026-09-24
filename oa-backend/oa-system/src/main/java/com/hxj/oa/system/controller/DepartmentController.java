package com.hxj.oa.system.controller;

import com.hxj.oa.common.annotation.Audit;
import com.hxj.oa.common.api.R;
import com.hxj.oa.common.security.RequirePerm;
import com.hxj.oa.common.security.UserContext;
import com.hxj.oa.system.dto.DeptSaveReq;
import com.hxj.oa.system.dto.DeptTreeVO;
import com.hxj.oa.system.entity.Department;
import com.hxj.oa.system.service.DepartmentService;
import com.hxj.oa.system.service.OrgAdminService;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 部门管理
 *
 * <p>读接口对全体登录用户开放（发起单据选部门要用），写接口挂 {@code system:dept}。
 *
 * <p>读与写共用同一路径前缀，但权限口径不同：读只要登录，写要权限点。
 * 写接口绝不能只靠登录态 —— 那样任何能登录的账号都能改组织架构。
 *
 * <p>写接口必须同时满足三件事，缺一不可：
 * <ol>
 *   <li>挂 {@code system:dept} 权限点（写接口绝不能只靠登录态）；</li>
 *   <li>补 {@code @Audit(module = "system")}（主数据变更必须留痕）；</li>
 *   <li>补对应的接口级用例（写接口的越权与唯一性只有用例能钉住）。</li>
 * </ol>
 *
 * <p>业务不变量（物化路径、引用守卫、不支持移动等）都在 {@link OrgAdminService}，
 * 那里逐条写了"为什么"。控制器只做权限、留痕与参数透传。
 */
@RestController
@RequestMapping("/api/depts")
@RequiredArgsConstructor
public class DepartmentController {

    private final DepartmentService departmentService;
    private final OrgAdminService orgAdminService;

    /**
     * 部门树（按 parentId 组成 children 层级），组织架构图与「选部门」下拉用。
     *
     * @param companyId 公司 ID；不传则取当前登录人的公司
     */
    @GetMapping("/tree")
    public R<List<DeptTreeVO>> tree(@RequestParam(required = false) Long companyId) {
        Long cid = companyId == null ? UserContext.require().getCompanyId() : companyId;
        return R.ok(departmentService.tree(cid));
    }

    /**
     * 部门平铺列表（无层级结构，需要树形请用 /tree）。
     *
     * @param companyId 公司 ID；不传则取当前登录人的公司
     */
    @GetMapping
    public R<List<Department>> list(@RequestParam(required = false) Long companyId) {
        Long cid = companyId == null ? UserContext.require().getCompanyId() : companyId;
        return R.ok(departmentService.listByCompany(cid));
    }

    /**
     * 新建部门。
     *
     * <p>parentId 传 null 或 0 表示一级中心；code 留空由后端按 D0xx 规则生成。
     * 名称、编码在同一公司内不允许重复。
     */
    @PostMapping
    @RequirePerm("system:dept")
    @Audit(module = "system", action = "createDept")
    public R<Department> create(@Valid @RequestBody DeptSaveReq req) {
        return R.ok(orgAdminService.createDept(req));
    }

    /**
     * 修改部门（名称 / 编码 / 负责人 / 排序 / 状态）。
     *
     * <p>⚠ <b>不支持调整上级部门</b>：传了与原值不同的 parentId 会直接报错（移动部门要重写整棵
     * 子树的路径，做一半更危险）。调整组织架构请新建部门后迁移人员。
     *
     * <p>⚠ <b>清空负责人要传 {@code leaderId: 0}</b>，传 null 等于"不改这个字段"（不会报错）。
     *
     * @param id 部门 ID
     */
    @PutMapping("/{id}")
    @RequirePerm("system:dept")
    @Audit(module = "system", action = "updateDept")
    public R<Department> update(@PathVariable Long id, @Valid @RequestBody DeptSaveReq req) {
        return R.ok(orgAdminService.updateDept(id, req));
    }

    /**
     * 删除部门（逻辑删除）。
     *
     * <p>三道引用守卫，任一不过都拒绝并在 msg 里说明原因：仍有子部门 / 仍有员工挂在该部门下 /
     * 仍挂着兼岗记录。
     *
     * @param id 部门 ID
     */
    @DeleteMapping("/{id}")
    @RequirePerm("system:dept")
    @Audit(module = "system", action = "deleteDept")
    public R<Void> delete(@PathVariable Long id) {
        orgAdminService.deleteDept(id);
        return R.ok(null, "已删除");
    }

}
