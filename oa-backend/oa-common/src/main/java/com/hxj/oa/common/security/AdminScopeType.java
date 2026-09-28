package com.hxj.oa.common.security;

/**
 * 管理范围类型（能【管】哪些部门的人的账号）。层级由窄到宽，多角色时取最宽者生效。
 *
 * <p><b>与 {@link DataScopeType} 是两个正交维度，不要合并</b>：
 * <ul>
 *   <li>{@link DataScopeType} 答「能【看】多少数据」—— 单据 / 台账的行级过滤，
 *       消费方 {@code DataScopeHelper}，载体 {@code role_data_scope}；</li>
 *   <li>{@link AdminScopeType} 答「能【管】哪些人」—— 用户管理接口的读与写，
 *       消费方 {@code AdminScopeHelper}，载体 {@code role_admin_scope}。</li>
 * </ul>
 * 同一个人完全可以「看全公司单据、但只能管本部门的人」，也可以反过来。
 * 把两者合成一个字段或一张表的直接后果是：调其中一维会静默改掉另一维。
 *
 * <p><b>只有三档，且三档都有消费点</b>（硬约束：声明的控制必须有引用点，
 * 「存在」≠「生效」，空转的控制比没有更危险）。刻意<b>不含</b>「指定部门集合」——
 * 将来确有需要时再加列，不预留"有列无消费点"的配置项。
 */
public enum AdminScopeType {

    /** 不能管理任何人的账号（缺省值：表里没有该角色的行时就是它） */
    NONE(1, "none"),
    /** 本部门及下级（用部门物化路径定位子树，与数据范围同一套算法） */
    DEPT_SUBTREE(2, "dept_subtree"),
    /** 全公司 */
    ALL(3, "all");

    /** 宽度等级，数字越大范围越宽；多角色时取最宽者生效 */
    private final int level;
    /** 接口里传的字符串编码（小写） */
    private final String code;

    AdminScopeType(int level, String code) {
        this.level = level;
        this.code = code;
    }

    public int getLevel() {
        return level;
    }

    public String getCode() {
        return code;
    }

    /**
     * 解析编码。<b>未知值 / null 一律回退 {@link #NONE}（最窄）</b>。
     *
     * <p>与 {@link DataScopeType#of} 回退 SELF 同一哲学：漏配或脏数据不该被解释成放权。
     * ⚠ 这也意味着 JWT 反序列化出来的旧 token（没有 adminScope 字段）会按 NONE 处理 ——
     * 表现为"加了这一档之后，旧 token 暂时管不了人"，重新登录即可。这是"权限是登录时刻
     * 快照"这条既有语义的正常延续，不是 bug。
     *
     * <p>⚠ 写路径<b>不要</b>用它做入参校验：它会静默降级，非法值会变成"成功响应 + 悄悄收窄"。
     * 写路径要用严格解析（见 {@code RoleAdminService} 里的 requireAdminScopeType）。
     */
    public static AdminScopeType of(String code) {
        if (code == null) {
            return NONE;
        }
        for (AdminScopeType t : values()) {
            if (t.code.equalsIgnoreCase(code)) {
                return t;
            }
        }
        return NONE;
    }

    /** 取更宽的范围 */
    public AdminScopeType wider(AdminScopeType other) {
        if (other == null) {
            return this;
        }
        return this.level >= other.level ? this : other;
    }

    /** 是否不受部门限制（可管理全公司）。用于「能否授予自己不具备的角色」这类特权提升判定。 */
    public boolean isUnrestricted() {
        return this == ALL;
    }
}
