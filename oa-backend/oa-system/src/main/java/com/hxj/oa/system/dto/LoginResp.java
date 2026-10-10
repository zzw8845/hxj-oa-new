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

    /** 可见菜单（sys_permission 菜单树驱动：组点行=分组标题、页面项行=菜单项，平铺下发；前端按 parentCode 组树纯渲染，可见性由角色绑定决定） */
    private List<MenuItem> menus;

    /** true=管理员创建账号/重置口令后的首次登录，前端必须先强制改密（见 AuthService#changePassword） */
    private boolean mustChangePassword;

    /** 菜单项 */
    @Data
    public static class MenuItem {
        /** 组点行 = 权限点编码（document:menu 等，仅作分组标题）；页面项行 = 菜单项编码（全冒号：模块:页面，如 document:work / admin:audit），前端自映射到页面路由 */
        private String code;
        /** 菜单名称，如「工作台」「单据中心」 */
        private String name;
        /** 页面项 = 所属组点编码；组点行 = null */
        private String parentCode;
        /** 前端页面标识（页面项才有值）：前端据此挂载页面组件（动态路由），如 work / forms */
        private String component;
        /** 排序号，小的在前 */
        private Integer sortNo;
    }
}
