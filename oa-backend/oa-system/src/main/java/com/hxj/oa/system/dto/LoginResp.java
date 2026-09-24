package com.hxj.oa.system.dto;

import com.hxj.oa.common.security.LoginUser;
import lombok.Data;

import java.util.List;

/** 登录响应 */
@Data
public class LoginResp {

    /** JWT，后续所有请求放在请求头 Authorization: Bearer {token} */
    private String token;

    /** 有效期（秒），当前为 43200（12 小时）；⚠ 没有刷新接口，到期须重新登录 */
    private long expiresIn;

    /** 当前登录用户（含 roleCodes、permCodes、deptId 等；⚠ 这些是登录时刻的快照，改完角色/部门要重新登录才生效） */
    private LoginUser user;

    /** 可见菜单（permType=1 的权限点，平铺列表，前端按 parentCode 组树） */
    private List<MenuItem> menus;

    /** true=管理员创建账号/重置口令后的首次登录，前端必须先强制改密（见 AuthService#changePassword） */
    private boolean mustChangePassword;

    /** 菜单项 */
    @Data
    public static class MenuItem {
        /** 菜单权限点编码，如 document:menu */
        private String code;
        /** 菜单名称，如「单据中心」 */
        private String name;
        /** 上级菜单编码，顶级为 null */
        private String parentCode;
        /** 排序号，小的在前 */
        private Integer sortNo;
    }
}
