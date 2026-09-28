package com.hxj.oa.common.util;

import com.hxj.oa.common.security.AdminScopeType;
import com.hxj.oa.common.security.LoginUser;

import java.util.ArrayList;
import java.util.List;

/**
 * 管理范围（能【管】哪些人的账号）SQL 片段生成器。
 *
 * <p><b>只管「管理动作」，不管「协作查询」。</b>这一点必须说清楚，否则很容易顺手扩大：
 * 用户管理面有两个读接口，语义完全不同 ——
 * <ul>
 *   <li>{@code GET /api/users}（全量，无门控）：<b>选审批人 / 查同事</b>的协作查询，
 *       每个能登录的人都得能用。若把它也按管理范围收窄，普通员工一发起单据就
 *       选不出本部门以外的审批人，且<b>界面没有任何提示</b>；</li>
 *   <li>{@code GET /api/users/page} 与全部用户写接口（挂 {@code system:user}）：
 *       <b>人员管理</b>动作，这里才是本类生效的地方。</li>
 * </ul>
 * 换句话说：本类回答"这个人能不能动那个账号"，不回答"这个人能不能看到那个名字"。
 *
 * <p>与 {@link DataScopeHelper} 的关系：两个正交维度，共用
 * {@link DataScopeHelper#deptSubtreeSubQuery} 定位部门子树，
 * 但**不要把两个维度合并**。
 *
 * <p>返回值约定（与 {@link DataScopeHelper} 刻意保持一致，接手人不必记两套规则）：
 * <ul>
 *   <li><b>不含前导 AND</b>：自行加上会拼出 {@code AND AND}，分页 count SQL 会被改写成畸形语句；</li>
 *   <li><b>无任何限制时返回 null</b>，调用方必须判空；</li>
 *   <li>{@code user == null}（理论上不可达，兜底）返回恒假而不是恒真 ——
 *       拿不准身份时收紧，不放宽。</li>
 * </ul>
 *
 * <p>安全：拼接值全部来自服务端（会话里的用户 ID / 公司 ID / 已校验的物化路径），
 * 不接受任何客户端传入的"归属"参数。这正是硬约束里那条：
 * <b>客户端可控的「资源归属」参数一律不接受传入</b>。
 */
public final class AdminScopeHelper {

    private AdminScopeHelper() {
    }

    /**
     * 生成「能管哪些人的账号」过滤片段（作用于 {@code sys_user}）。
     *
     * @param alias 表别名，如 "u"；无别名时传空串或 null
     * @param user  当前登录用户
     * @return 不含前导 AND 的片段；无任何限制时返回 null
     */
    public static String applyClause(String alias, LoginUser user) {
        return clause(alias, user, "dept_id");
    }

    /**
     * 「目标<b>部门</b>是否在管理范围内」的过滤片段（作用于 {@code department} 表）。
     *
     * <p>为什么需要第二条：新建员工 / 调岗时<b>目标账号还不存在</b>，没有 id 可以判，
     * 能判的只有"要把他放到哪个部门"。少了它，一个只管本部门的管理员可以创建一个
     * 挂在总经理办公室的账号，一步跳出自己的范围。
     *
     * <p>它套的还是同一段子树子查询，只是判断列从 {@code dept_id} 换成 department
     * 自身的主键 {@code id} —— 语义是"这个部门节点在不在我的子树里"。
     */
    public static String deptCoveredClause(String alias, LoginUser user) {
        return clause(alias, user, "id");
    }

    /**
     * 唯一的片段生成实现。{@code deptColumn} 只影响"拿哪一列去比子树"，
     * 其余规则（公司隔离、三档语义、返回约定）完全一致 —— 两处各写一份迟早分叉。
     */
    private static String clause(String alias, LoginUser user, String deptColumn) {
        if (user == null) {
            return "1 = 0";
        }
        String a = (alias == null || alias.isBlank()) ? "" : alias + ".";
        AdminScopeType type = user.getAdminScope() == null ? AdminScopeType.NONE : user.getAdminScope();
        List<String> parts = new ArrayList<>();

        // 多公司隔离：任何范围都必须带公司条件（与 DataScopeHelper 同一取舍）
        if (user.getCompanyId() != null) {
            parts.add(a + "company_id = " + user.getCompanyId());
        }

        switch (type) {
            case ALL -> {
                // 不加额外限制；若连 companyId 都没有，则整段为空 ⇒ 返回 null（不限）
            }
            case DEPT_SUBTREE ->
                    parts.add(a + deptColumn + " IN (" + DataScopeHelper.deptSubtreeSubQuery(user) + ")");
            // NONE：谁都不能管。用恒假而不是"返回 null"—— 漏配是最窄，不是最宽。
            case NONE -> parts.add("1 = 0");
        }

        if (parts.isEmpty()) {
            return null;
        }
        return String.join(" AND ", parts);
    }

    /**
     * 该用户是否**完全不**受管理范围限制（可管理全公司）。
     *
     * <p>用于「特权提升」判定：非 all 的管理员不得给他人授予自己不具备的角色。
     * 不要把本方法与"是不是 ADMIN 角色"混为一谈 —— 后者是本项目明令禁止的
     * 角色字符串判权（见 {@link LoginUser} 里删掉 {@code hasRole} 的那段说明）。
     *
     * @param user 当前登录用户；null 视为受限
     */
    public static boolean isUnrestricted(LoginUser user) {
        return user != null && user.getAdminScope() != null && user.getAdminScope().isUnrestricted();
    }

    /**
     * 管理范围的中文名，供接口直接回显（避免每个前端各维护一份映射）。
     */
    public static String label(String code) {
        return switch (code == null ? "none" : code) {
            case "none" -> "不管人";
            case "dept_subtree" -> "本部门及下级";
            case "all" -> "全公司";
            default -> code;
        };
    }
}
