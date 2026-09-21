package com.hxj.oa.system.controller;

import com.hxj.oa.common.api.R;
import com.hxj.oa.common.security.UserContext;
import com.hxj.oa.system.dto.DeptTreeVO;
import com.hxj.oa.system.entity.Department;
import com.hxj.oa.system.service.DepartmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 部门管理：读接口对全体登录用户开放（发起单据选部门要用）。
 *
 * <p><b>当前本控制器只有读接口，没有写接口</b>（写接口曾实现过又被回退）。
 * 若要新增 POST/PUT/DELETE，必须同时满足三件事，缺一不可：
 * <ol>
 *   <li>挂 {@code system:dept} 权限点（写接口绝不能只靠登录态）；</li>
 *   <li>补 {@code @Audit(module = "system")}（主数据变更必须留痕）；</li>
 *   <li>补回对应的接口级用例（写接口的越权与唯一性只有用例能钉住）。</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/depts")
@RequiredArgsConstructor
public class DepartmentController {

    private final DepartmentService departmentService;

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

}
