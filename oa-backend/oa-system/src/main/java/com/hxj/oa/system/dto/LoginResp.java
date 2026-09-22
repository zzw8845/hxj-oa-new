package com.hxj.oa.system.dto;

import com.hxj.oa.common.security.LoginUser;
import lombok.Data;

import java.util.List;

@Data
public class LoginResp {

    private String token;
    private long expiresIn;
    private LoginUser user;
    /** 可见菜单（permType=1 的权限点） */
    private List<MenuItem> menus;
    /** true=管理员创建账号/重置口令后的首次登录，前端必须先强制改密（见 AuthService#changePassword） */
    private boolean mustChangePassword;

    @Data
    public static class MenuItem {
        private String code;
        private String name;
        private String parentCode;
        private Integer sortNo;
    }
}
