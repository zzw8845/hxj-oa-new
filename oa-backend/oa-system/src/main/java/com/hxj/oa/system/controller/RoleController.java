package com.hxj.oa.system.controller;

import com.hxj.oa.common.annotation.Audit;
import com.hxj.oa.common.api.R;
import com.hxj.oa.common.security.RequirePerm;
import com.hxj.oa.common.security.UserContext;
import com.hxj.oa.system.dto.RoleSaveReq;
import com.hxj.oa.system.dto.RoleVO;
import com.hxj.oa.system.service.RoleAdminService;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 角色管理。
 *
 * <p>列表返回 {@link RoleVO} 而非裸实体：前端「配置权限」弹窗需要回填权限点与数据范围，
 * 原先这些字段不在响应里，界面只能显示占位文案。
 *
 * <p>写接口挂 {@code system:role}。权限点与数据范围拆成独立子资源，
 * 因为它们在界面上是两个动作，语义上也是两件事。
 */
@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleAdminService roleAdminService;

    @GetMapping
    public R<List<RoleVO>> list(@RequestParam(required = false) Long companyId) {
        Long cid = companyId == null ? UserContext.require().getCompanyId() : companyId;
        return R.ok(roleAdminService.listWithDetail(cid));
    }

    @GetMapping("/{id}")
    public R<RoleVO> detail(@PathVariable Long id) {
        return R.ok(roleAdminService.detail(id));
    }

    /* ------------------------------------------------------------------ 写 */

    @PostMapping
    @RequirePerm("system:role")
    @Audit(module = "permission", action = "createRole")
    public R<RoleVO> create(@Valid @RequestBody RoleSaveReq req) {
        return R.ok(roleAdminService.create(req), "角色已创建");
    }

    @PutMapping("/{id}")
    @RequirePerm("system:role")
    @Audit(module = "permission", action = "updateRole")
    public R<RoleVO> update(@PathVariable Long id, @Valid @RequestBody RoleSaveReq req) {
        return R.ok(roleAdminService.update(id, req), "角色已更新");
    }

    /** 全量覆盖角色权限点 */
    @PutMapping("/{id}/permissions")
    @RequirePerm("system:role")
    @Audit(module = "permission", action = "grantPerm")
    public R<RoleVO> updatePermissions(@PathVariable Long id, @RequestBody PermAssignReq req) {
        return R.ok(roleAdminService.updatePermissions(id, req.getPermCodes()), "权限已保存");
    }

    /** 配置数据范围（行级权限） */
    @PutMapping("/{id}/data-scope")
    @RequirePerm("system:role")
    @Audit(module = "permission", action = "dataScope")
    public R<RoleVO> updateDataScope(@PathVariable Long id, @RequestBody DataScopeReq req) {
        return R.ok(roleAdminService.updateDataScope(id, req.getScopeType(), req.getScopeDeptIds()),
                "数据范围已保存");
    }

    @DeleteMapping("/{id}")
    @RequirePerm("system:role")
    @Audit(module = "permission", action = "deleteRole")
    public R<Void> delete(@PathVariable Long id) {
        roleAdminService.delete(id);
        return R.ok(null, "角色已删除");
    }

    @Data
    public static class PermAssignReq {
        private List<String> permCodes;
    }

    @Data
    public static class DataScopeReq {
        private String scopeType;
        private List<Long> scopeDeptIds;
    }
}
