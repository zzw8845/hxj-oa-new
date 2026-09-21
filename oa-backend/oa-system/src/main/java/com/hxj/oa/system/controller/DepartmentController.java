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
 * 部门管理：读接口对全体登录用户开放（发起单据选部门要用），写接口挂 {@code system:dept}。
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

    @GetMapping("/tree")
    public R<List<DeptTreeVO>> tree(@RequestParam(required = false) Long companyId) {
        Long cid = companyId == null ? UserContext.require().getCompanyId() : companyId;
        return R.ok(departmentService.tree(cid));
    }

    @GetMapping
    public R<List<Department>> list(@RequestParam(required = false) Long companyId) {
        Long cid = companyId == null ? UserContext.require().getCompanyId() : companyId;
        return R.ok(departmentService.listByCompany(cid));
    }

    @PostMapping
    @RequirePerm("system:dept")
    @Audit(module = "system", action = "createDept")
    public R<Department> create(@Valid @RequestBody DeptSaveReq req) {
        return R.ok(orgAdminService.createDept(req));
    }

    @PutMapping("/{id}")
    @RequirePerm("system:dept")
    @Audit(module = "system", action = "updateDept")
    public R<Department> update(@PathVariable Long id, @Valid @RequestBody DeptSaveReq req) {
        return R.ok(orgAdminService.updateDept(id, req));
    }

    @DeleteMapping("/{id}")
    @RequirePerm("system:dept")
    @Audit(module = "system", action = "deleteDept")
    public R<Void> delete(@PathVariable Long id) {
        orgAdminService.deleteDept(id);
        return R.ok(null, "已删除");
    }

}
