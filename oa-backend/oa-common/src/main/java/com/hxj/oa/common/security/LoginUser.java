package com.hxj.oa.common.security;

import lombok.Builder;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Collections;
import java.util.Set;

/**
 * 登录用户上下文（由 JWT 解析得到）。
 * 注意：保留了无参构造，便于 Jackson 反序列化。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginUser implements Serializable {

    private Long userId;
    private String account;
    private String realName;
    private Long companyId;
    private Long deptId;
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

    public boolean hasRole(String roleCode) {
        return getRoleCodes().contains(roleCode);
    }

    public boolean hasPerm(String permCode) {
        return getPermCodes().contains(permCode);
    }
}
