package com.hxj.oa.common.util;

import com.hxj.oa.common.security.DataScopeType;
import com.hxj.oa.common.security.LoginUser;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 数据范围（行级权限）SQL 片段生成器。
 *
 * 使用方式（服务层）：
 * <pre>
 *   String clause = DataScopeHelper.buildClause("d", UserContext.require());
 *   if (clause != null) {
 *       wrapper.apply(clause);     // MyBatis-Plus 的 apply() 会自动用 AND 连接
 *   }
 * </pre>
 *
 * 【重要】返回值不含前导 AND。若自行加上 "AND"，会拼出 "AND AND"，
 * 导致 JSqlParser 解析失败、分页 count SQL 被改写成畸形语句。
 * 无任何限制时返回 null，调用方必须判空。
 *
 * 安全：所有拼接值来自服务端（用户 ID / 部门 ID / 已校验的物化路径），
 *      物化路径额外做字符白名单校验，杜绝 SQL 注入。
 *
 * 【本类只答「能看多少数据」】「能管哪些人的账号」是<b>另一个正交维度</b>，
 * 在 {@code AdminScopeHelper}（载体 role_admin_scope / 字段 LoginUser.adminScope）。
 * 两者共用本类的 {@link #deptSubtreeSubQuery}，但<b>不要</b>把维度合并成一个字段：
 * 那会让"调某一维"静默改掉另一维。
 */
public final class DataScopeHelper {

    private static final Pattern PATH_SAFE = Pattern.compile("^/[0-9/]*$");

    private DataScopeHelper() {
    }

    /**
     * 生成行级过滤片段（不含前导 AND；无限制时返回 null）。
     *
     * @param alias        表别名，如 "d"；无别名时传空串
     * @param user         当前登录用户
     * @param scopeDeptIds 若为 custom_dept，用这些部门 ID；否则用发起人部门及下级
     */
    public static String buildClause(String alias, LoginUser user) {
        return buildClause(alias, user, null);
    }

    public static String buildClause(String alias, LoginUser user, Set<Long> scopeDeptIds) {
        if (user == null) {
            return "1 = 0";
        }
        String a = (alias == null || alias.isBlank()) ? "" : alias + ".";
        DataScopeType type = user.getDataScope() == null ? DataScopeType.SELF : user.getDataScope();
        List<String> parts = new ArrayList<>();

        // 多公司隔离：任何范围都必须带公司条件
        if (user.getCompanyId() != null) {
            parts.add(a + "company_id = " + user.getCompanyId());
        }

        switch (type) {
            case COMPANY -> {
                // 不加额外限制
            }
            case CENTER, DEPT -> parts.add(a + "dept_id IN (" + deptSubtreeSubQuery(user) + ")");
            case CUSTOM_DEPT -> {
                if (scopeDeptIds != null && !scopeDeptIds.isEmpty()) {
                    parts.add(a + "dept_id IN (" + joinIds(scopeDeptIds) + ")");
                } else {
                    parts.add(a + "dept_id IN (" + deptSubtreeSubQuery(user) + ")");
                }
            }
            case SELF -> parts.add(a + "applicant_id = " + user.getUserId());
            default -> parts.add(a + "applicant_id = " + user.getUserId());
        }

        if (parts.isEmpty()) {
            return null;
        }
        return String.join(" AND ", parts);
    }

    /**
     * 「本部门及下级」的子查询（用物化路径一次定位子树，避免递归 CTE 的深度限制）。
     *
     * <p><b>公开出来是为了让「部门子树」只有一份实现</b>：{@code AdminScopeHelper}
     * 的 {@code dept_subtree} 档也要定位同一棵树，各写一份迟早会在某次修改中分叉
     * （PATH_SAFE 白名单校验、company 过滤、path 为空时的降级，谁都可能漏一处）。
     * 这份实现只依赖 {@link LoginUser} 的 deptId / deptPath，与"用它来过滤哪张表"无关。
     *
     * <p>用法：拼进 {@code <列> IN ( ... )}。返回值<b>是不带外层括号的子查询</b>，
     * 调用方负责补括号。
     */
    public static String deptSubtreeSubQuery(LoginUser user) {
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT id FROM department WHERE deleted = 0");
        if (user.getCompanyId() != null) {
            sb.append(" AND company_id = ").append(user.getCompanyId());
        }
        String path = user.getDeptPath();
        if (path != null && PATH_SAFE.matcher(path).matches() && !"/".equals(path)) {
            // path 形如 /8/，LIKE '/8/%' 恰好命中自身与全部子孙
            sb.append(" AND path LIKE '").append(path).append("%'");
        } else if (user.getDeptId() != null) {
            sb.append(" AND id = ").append(user.getDeptId());
        } else {
            sb.append(" AND 1 = 0");
        }
        return sb.toString();
    }

    private static String joinIds(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "0";
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Long id : ids) {
            if (id == null) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            sb.append(id);
            first = false;
        }
        return sb.isEmpty() ? "0" : sb.toString();
    }
}
