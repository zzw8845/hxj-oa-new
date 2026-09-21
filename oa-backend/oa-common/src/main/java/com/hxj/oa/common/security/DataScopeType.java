package com.hxj.oa.common.security;

/**
 * 数据范围类型（行级权限）。层级由窄到宽，多角色时取最宽者生效。
 */
public enum DataScopeType {

    /** 仅本人 */
    SELF(1, "self"),
    /** 本部门及下级 */
    DEPT(2, "dept"),
    /** 本中心 */
    CENTER(3, "center"),
    /** 本部门及下级（可指定部门集合） */
    CUSTOM_DEPT(4, "custom_dept"),
    /** 全公司 */
    COMPANY(5, "company");

    private final int level;
    private final String code;

    DataScopeType(int level, String code) {
        this.level = level;
        this.code = code;
    }

    public int getLevel() {
        return level;
    }

    public String getCode() {
        return code;
    }

    public static DataScopeType of(String code) {
        if (code == null) {
            return SELF;
        }
        for (DataScopeType t : values()) {
            if (t.code.equalsIgnoreCase(code)) {
                return t;
            }
        }
        return SELF;
    }

    /** 取更宽的范围 */
    public DataScopeType wider(DataScopeType other) {
        if (other == null) {
            return this;
        }
        return this.level >= other.level ? this : other;
    }
}
