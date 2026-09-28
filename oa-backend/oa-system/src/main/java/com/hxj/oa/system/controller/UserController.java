package com.hxj.oa.system.controller;

import com.hxj.oa.common.annotation.Audit;
import com.hxj.oa.common.api.PageResult;
import com.hxj.oa.common.api.R;
import com.hxj.oa.common.security.LoginUser;
import com.hxj.oa.common.security.RequirePerm;
import com.hxj.oa.common.security.UserContext;
import com.hxj.oa.system.dto.UserSaveReq;
import com.hxj.oa.system.dto.UserVO;
import com.hxj.oa.system.service.UserAdminService;
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
 *
 * <p><b>读接口的暴露面控制（接手人必读）</b>：全开的 {@code GET /api/users} 已剥离
 * 手机号/邮箱，只返回组织架构必需字段；带个人信息的读走 {@code GET /api/users/page}
 * （挂 {@code system:user}）。本轮另外删掉了三个既无权限点、又没有任何调用方的读端点
 * （{@code GET /api/users/{id}}、{@code GET /api/users/roles}）—— 它们不是"备用接口"
 * 而是绕过门禁的旁路；单查用户请用 {@code /api/users/page?keyword=}，
 * 角色列表请用 {@code GET /api/roles}。
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserAdminService userAdminService;

    /**
     * 当前公司用户列表（含部门名/岗位名/角色，供前端「选择审批人/被代理人」与人员管理页共用）。
     *
     * <p>刻意保留全量：选人下拉要在客户端对全量做模糊搜索，分页反而做不了；
     * 量级受公司规模天然约束（一家公司几千人是上限），不构成无界增长表。
     *
     * <p><b>为什么这个读接口不挂权限点、而 {@code /page} 挂了</b>：选审批人、查同事是
     * 「发起单据」链路的一环，任何能登录的人都得能用；若挂 {@code system:user}，
     * 普通员工一发起单据就选不出审批人。代价是暴露面变大，因此这里做了两件事：
     * <ol>
     *   <li><b>剥离个人敏感字段</b>（{@code phone}/{@code email}）—— 选人只需要
     *       姓名/工号/部门/岗位，手机号邮箱属个人信息，只在挂了权限点的
     *       {@code /api/users/page} 里返回（见 {@code UserAdminService#stripPersonalInfo}）。</li>
     *   <li>返回的组织架构信息（部门、岗位、角色）对内部系统而言是协作必需。</li>
     * </ol>
     *
     * @param companyId 公司 ID；不传则取当前登录人的公司
     */
    @GetMapping
    public R<List<UserVO>> list(@RequestParam(required = false) Long companyId) {
        LoginUser me = UserContext.require();
        return R.ok(userAdminService.listWithDetail(companyId == null ? me.getCompanyId() : companyId));
    }

    /**
     * 人员管理表格的分页接口（过滤与分页都在 SQL 里完成）。
     *
     * <p>与 {@code GET /api/users} 刻意保留两份：后者给选人下拉用，**必须**全量
     * （客户端要对全量做模糊搜索）；前者给表格用，必须服务端分页。合成一个接口会顾此失彼 ——
     * 要么下拉搜不全人，要么表格一次把全公司拉下来。
     *
     * @param pageNum   页码，从 1 开始
     * @param pageSize  每页条数
     * @param keyword   按姓名 / 账号 / 工号模糊搜索
     * @param companyId 公司 ID；不传则取当前登录人的公司
     */
    @GetMapping("/page")
    @RequirePerm("system:user")
    public R<PageResult<UserVO>> page(@RequestParam(required = false) Integer pageNum,
                                      @RequestParam(required = false) Integer pageSize,
                                      @RequestParam(required = false) String keyword,
                                      @RequestParam(required = false) Long companyId) {
        LoginUser me = UserContext.require();
        Long cid = companyId == null ? me.getCompanyId() : companyId;
        return R.ok(userAdminService.pageWithDetail(cid, pageNum, pageSize, keyword));
    }

    /**
     * 新建员工。
     *
     * <p>姓名 / 工号 / 账号 / 密码必填（密码即初始密码，后端会置"待改密"标记，
     * 登录响应里的 {@code mustChangePassword} 会告诉前端是否弹强制改密框）。
     * 部门与岗位支持「传 ID 或传名称」：传了 ID 用 ID，只传名称按「公司 + 名称」反查。
     */
    @PostMapping
    @RequirePerm("system:user")
    @Audit(module = "permission", action = "createUser")
    public R<UserVO> create(@Valid @RequestBody UserSaveReq req) {
        return R.ok(userAdminService.create(req), "员工已创建");
    }

    /**
     * 修改员工信息。
     *
     * <p>⚠ 姓名 / 工号 / 账号是 {@code @NotBlank}，<b>即使只想改手机号也必须一起传</b>
     * （缺任一 → 400 参数校验失败）。密码留空表示不改密码。
     *
     * <p>⚠ 不能改「当前登录账号自己」的启停状态与角色，会明确报错。
     *
     * @param id 用户 ID
     */
    @PutMapping("/{id}")
    @RequirePerm("system:user")
    @Audit(module = "permission", action = "updateUser")
    public R<UserVO> update(@PathVariable Long id, @Valid @RequestBody UserSaveReq req) {
        return R.ok(userAdminService.update(id, req), "员工信息已更新");
    }

    /** 单独调整角色（界面上的「调整角色」入口）。⚠ 全量覆盖，未传的角色会被移除 */
    @PutMapping("/{id}/roles")
    @RequirePerm("system:user")
    @Audit(module = "permission", action = "assignRoles")
    public R<UserVO> updateRoles(@PathVariable Long id, @RequestBody RoleAssignReq req) {
        return R.ok(userAdminService.updateRoles(id, req.getRoleCodes()), "角色已调整");
    }

    /**
     * 停用并删除员工（逻辑删除）。
     *
     * @param id 用户 ID
     */
    @DeleteMapping("/{id}")
    @RequirePerm("system:user")
    @Audit(module = "permission", action = "deleteUser")
    public R<Void> delete(@PathVariable Long id) {
        userAdminService.delete(id);
        return R.ok(null, "员工已停用并删除");
    }

    /** 角色调整请求 */
    @Data
    public static class RoleAssignReq {
        /** 要设置的角色编码列表；**全量覆盖**，未传的会被移除 */
        private List<String> roleCodes;
    }
}
