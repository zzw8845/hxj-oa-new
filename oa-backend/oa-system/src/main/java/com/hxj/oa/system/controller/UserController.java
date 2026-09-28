package com.hxj.oa.system.controller;

import com.hxj.oa.common.annotation.Audit;
import com.hxj.oa.common.api.PageResult;
import com.hxj.oa.common.api.R;
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
 * <p><b>读接口为什么一开一收 —— 权限归权限，字段归字段</b>：
 * {@code GET /api/users} 是「选审批人 / 查同事」的协作查询，必须人人可用
 * （挂 {@code system:user} 的话，普通员工一发起单据就选不出审批人）；
 * {@code /api/users/page} 与 {@code /api/users/{id}} 是「人员管理」动作，挂 {@code system:user}。
 * <b>三者返回的 {@code UserVO} 字段完全一致</b>（都含手机号/邮箱）—— 门控差异只决定「能不能调」，
 * 不体现为"字段多少"。同一个人在不同接口有两副字段面貌，会让接手人无法从契约判断字段是否存在。
 *
 * <p>另有 {@code GET /api/users/roles}（可选角色列表）已删除：它与 {@code GET /api/roles}
 * 语义重复，属接口面冗余；取角色列表统一走 {@code GET /api/roles}。
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserAdminService userAdminService;

    /**
     * 当前公司用户列表（含部门名、岗位名、角色、联系方式，供「选择审批人 / 被代理人」与人员管理页共用）。
     *
     * <p>刻意保留全量：选人时要边输入边即时过滤候选，分页取不全；
     * 量级受公司规模天然约束（一家公司几千人是上限），不构成无界增长表。
     *
     * <p><b>为什么这个读接口不挂权限点、而 {@code /page} 挂了</b>：选审批人、查同事是
     * 「发起单据」链路的一环，任何能登录的人都得能用；若挂 {@code system:user}，
     * 普通员工一发起单据就选不出审批人。
     *
     * <p>⚠ 这是<b>权限</b>层面的取舍、<b>不影响字段</b>：本接口返回的 {@code UserVO} 与
     * {@code /api/users/page}、{@code /api/users/{id}} <b>完全一致</b>（都含手机号/邮箱）。
     * 内部 OA 通讯录里同事联系方式本属可见信息，不需要靠"少返回两个字段"来做安全；
     * 若客户将来确有"普通员工不得查看同事联系方式"的合规要求，应做成<b>显式、可配置</b>的策略，
     * 而不是在这里隐式抹字段（那会让同一个 VO 在不同接口出现两副面貌，接手人无法从契约判断）。
     *
     * <p>公司一律取登录态，<b>不接受客户端传入</b>（客户端传参不可信，理由见
     * {@code DepartmentController#tree()} 上关于跨公司读的说明）。
     */
    @GetMapping
    public R<List<UserVO>> list() {
        return R.ok(userAdminService.listWithDetail(UserContext.require().getCompanyId()));
    }

    /**
     * 人员管理表格的分页接口（过滤与分页都在 SQL 里完成）。
     *
     * <p>与 {@code GET /api/users} 刻意保留两份：后者供「选人」场景一次取全量
     * （该场景要边输入边即时过滤）；前者供表格分页，不随公司人数放大单次响应。
     * 合成一个接口会顾此失彼 —— 要么选人端拿不全人，要么表格一次把全公司拉下来。
     *
     * <p>公司一律取登录态，不接受客户端传入（同上）。
     *
     * @param pageNum  页码，从 1 开始
     * @param pageSize 每页条数
     * @param keyword  按姓名 / 账号 / 工号模糊搜索
     */
    @GetMapping("/page")
    @RequirePerm("system:user")
    public R<PageResult<UserVO>> page(@RequestParam(required = false) Integer pageNum,
                                      @RequestParam(required = false) Integer pageSize,
                                      @RequestParam(required = false) String keyword) {
        return R.ok(userAdminService.pageWithDetail(UserContext.require().getCompanyId(),
                pageNum, pageSize, keyword));
    }

    /**
     * 单个员工详情（含手机号 / 邮箱 / 部门 / 岗位 / 角色）。
     *
     * <p>挂 {@code system:user} 是因为它属于「人员管理」动作；
     * 它与全开的 {@code GET /api/users} <b>字段一致</b>（门控差异只决定「能不能调」）。
     *
     * @param id 用户 ID
     */
    @GetMapping("/{id}")
    @RequirePerm("system:user")
    public R<UserVO> detail(@PathVariable Long id) {
        return R.ok(userAdminService.detail(id));
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
