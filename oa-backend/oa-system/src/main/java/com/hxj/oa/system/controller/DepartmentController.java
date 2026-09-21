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
 * 部门管理：读接口对全体登录用户开放（发起单据选部门要用），
 * 写接口统一挂 {@code system:dept} 权限点。
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
