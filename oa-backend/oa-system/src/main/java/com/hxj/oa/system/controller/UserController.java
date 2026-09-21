package com.hxj.oa.system.controller;

import com.hxj.oa.common.annotation.Audit;
import com.hxj.oa.common.api.R;
import com.hxj.oa.common.security.LoginUser;
import com.hxj.oa.common.security.RequirePerm;
import com.hxj.oa.common.security.UserContext;
import com.hxj.oa.system.dto.UserSaveReq;
import com.hxj.oa.system.dto.UserVO;
import com.hxj.oa.system.entity.SysRole;
import com.hxj.oa.system.service.UserAdminService;
import com.hxj.oa.system.service.UserService;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 人员管理。
 *
 * <p>读接口对全体登录用户开放（选审批人、查同事都要用）；
 * <b>写接口统一挂 {@code system:user} 权限点</b>——这是本轮加固补上的缺口：
 * 在加 {@code @RequirePerm} 之前，任何能登录的账号都能调用管理接口。
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final UserAdminService userAdminService;

    /**
     * 当前公司用户列表（含部门名/岗位名/角色，供前端「选择审批人/被代理人」与人员管理页共用）。
     *
     * <p>刻意保留全量：选人下拉要在客户端对全量做模糊搜索，分页反而做不了；
     * 量级受公司规模天然约束（一家公司几千人是上限），不构成无界增长表。
     */
    @GetMapping
    public R<List<UserVO>> list(@RequestParam(required = false) Long companyId) {
        LoginUser me = UserContext.require();
        return R.ok(userAdminService.listWithDetail(companyId == null ? me.getCompanyId() : companyId));
    }

    @GetMapping("/{id}")
    public R<UserVO> get(@PathVariable Long id) {
        return R.ok(userAdminService.detail(id));
    }

    @GetMapping("/roles")
    public R<List<SysRole>> roles(@RequestParam(required = false) Long companyId) {
        LoginUser me = UserContext.require();
        return R.ok(userService.listRoles(companyId == null ? me.getCompanyId() : companyId));
    }

    /* ------------------------------------------------------------------ 写 */

    @PostMapping
    @RequirePerm("system:user")
    @Audit(module = "permission", action = "createUser")
    public R<UserVO> create(@Valid @RequestBody UserSaveReq req) {
        return R.ok(userAdminService.create(req), "员工已创建");
    }

    @PutMapping("/{id}")
    @RequirePerm("system:user")
    @Audit(module = "permission", action = "updateUser")
    public R<UserVO> update(@PathVariable Long id, @Valid @RequestBody UserSaveReq req) {
        return R.ok(userAdminService.update(id, req), "员工信息已更新");
    }

    /** 单独调整角色（界面上的「调整角色」入口） */
    @PutMapping("/{id}/roles")
    @RequirePerm("system:user")
    @Audit(module = "permission", action = "assignRoles")
    public R<UserVO> updateRoles(@PathVariable Long id, @RequestBody RoleAssignReq req) {
        return R.ok(userAdminService.updateRoles(id, req.getRoleCodes()), "角色已调整");
    }

    @DeleteMapping("/{id}")
    @RequirePerm("system:user")
    @Audit(module = "permission", action = "deleteUser")
    public R<Void> delete(@PathVariable Long id) {
        userAdminService.delete(id);
        return R.ok(null, "员工已停用并删除");
    }

    @Data
    public static class RoleAssignReq {
        private List<String> roleCodes;
    }
}
