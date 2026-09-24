package com.hxj.oa.common.security;

import lombok.Builder;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Collections;
import java.util.Set;

/**
 * 登录用户上下文（由 JWT 解析得到）。GET /api/auth/me 返回的就是它。
 *
 * <p>⚠ <b>这里是「登录那一刻」的快照</b>：token 里装的就是下面这些字段，
 * 后端不查库。所以管理员改了某人的角色 / 部门 / 权限之后，
 * <b>该用户必须重新登录才会生效</b>（没有 refresh 接口）。
 * 前端不要把 permCodes / roleCodes 当作"实时权限"去做安全判断 —— 它只够用来控制菜单显隐。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginUser implements Serializable {

    /** 用户 ID */
    private Long userId;
    /** 登录账号（唯一） */
    private String account;
    /** 姓名 */
    private String realName;
    /** 所属公司 ID */
    private Long companyId;
    /** 所属部门 ID；管理员账号可能为 null（未挂部门） */
    private Long deptId;
    /** 所属部门名称；与 deptId 同为登录时快照 */
    private String deptName;
    /** 部门物化路径，如 /8/，用于「本部门及下级」数据范围判定 */
    private String deptPath;
    /** 角色编码集合 */
    private Set<String> roleCodes;
    /** 权限点编码集合 */
    private Set<String> permCodes;
    /** 生效的数据范围（多角色取最宽） */
    private DataScopeType dataScope;
    /** 自定义部门范围（custom_dept 时使用） */
    private Set<Long> scopeDeptIds;

    public Set<String> getRoleCodes() {
        return roleCodes == null ? Collections.emptySet() : roleCodes;
    }

    public Set<String> getPermCodes() {
        return permCodes == null ? Collections.emptySet() : permCodes;
    }

    /* ------------------------------------------------------------------
     * 【此处刻意没有 hasRole(String)】—— 不要加回来。
     *
     * 2026-09-23 前这里有一个 `public boolean hasRole(String roleCode)`，
     * 被四处业务代码用来做「管理员特权」判定：
     *   FlowRuntimeService#assertAssignee（可代任意人批准 —— 全项目唯一真实越权）
     *   DocumentService#assertVisible、AttachmentService#load/#delete
     * 根因是它**太好用**：拿到一个字符串就能绕过整条鉴权链，而且调用点在
     * 业务服务里，代码评审时看见的只是"一行 if"，看不出这是在开特权。
     *
     * 收敛方式不是"补文档要求大家别用"，而是**删掉这个方法**：
     * 四处旁路去掉后该方法零调用，删掉它则第 5 处旁路**编译不过**。
     * 这是唯一真正生效的护栏 —— 本项目的所有构建入口都带 -DskipTests
     * （deploy.sh / 启动联调版.command / init_and_run.sh / README），
     * 写在测试或 ArchUnit 里的规则等于没有。
     *
     * 需要"管理员能做某事"时，正确的落点是下面二者之一，都不依赖角色字符串：
     *   · 读（看得见哪些数据）→ role_data_scope 行级范围，见 DataScopeHelper
     *   · 写（能不能做某动作）→ 权限点 @RequirePerm + PermInterceptor
     * 而"谁是审批人"的唯一事实是流程节点的指派规则（FlowNodeAssignee）。
     * ------------------------------------------------------------------ */

    /**
     * 是否持有某权限点。
     *
     * <p>与 {@link #getPermCodes()} 同源。注意：<b>接口级门控不靠它</b> ——
     * 那是 {@code PermInterceptor} + {@code @RequirePerm} 在进控制器之前判的，
     * 用同一份 JWT 里的 permCodes。本方法留给"服务层内部再判一次"的场景，
     * 当前无调用点，不是遗漏。
     */
    public boolean hasPerm(String permCode) {
        return getPermCodes().contains(permCode);
    }
}
