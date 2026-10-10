package com.hxj.oa.system.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 权限点新建 / 编辑请求（权限点管理界面用）。
 *
 * <p><b>编码规则</b>：全冒号风格（对齐若依 perms 串）——菜单项 {@code 模块:页面}、
 * 操作点 {@code 模块:动作}（可三级），组点 {@code *:menu}。见《接口交接文档》编码对照表。
 *
 * <p><b>code 创建后不可改</b>：它同时是角色绑定（role_permission.perm_code）、
 * JWT permCodes、后端 {@code @RequirePerm} 注解的三方契约，改名等于静默废掉一批授权
 * （接口交接文档「权限编码」节有完整说明）。
 */
@Data
public class PermissionSaveReq {

    /** 权限点编码，如 report:export。新建必填且全库唯一；编辑不可改 */
    @NotBlank(message = "权限编码不能为空")
    @Size(max = 128, message = "权限编码不能超过 128 字")
    @Pattern(regexp = "^[a-z][a-z0-9]*(?::[a-zA-Z][a-zA-Z0-9]*)*$",
            message = "编码须为冒号风格：小写开头的模块段 + 冒号 + 段名，如 report:export / report:export:submit")
    private String code;

    /** 权限点名称（中文展示用） */
    @NotBlank(message = "权限点名称不能为空")
    @Size(max = 64, message = "权限点名称不能超过 64 字")
    private String name;

    /** 类型 1菜单(组点+页面项) 2操作-写 3操作-查询/审批 */
    @NotNull(message = "类型不能为空")
    @Min(value = 1, message = "类型只能是 1/2/3")
    @Max(value = 3, message = "类型只能是 1/2/3")
    private Integer permType;

    /** 父权限点编码；空 = 一级（组点） */
    @Size(max = 128, message = "父级编码不能超过 128 字")
    private String parentCode;

    /**
     * 前端页面标识（仅菜单项必填，如 work）：前端据此挂载页面组件，「加一行=点亮一个已有页面」。
     * 页面代码本身随前端发版——这里配的是门牌，造不出门牌后面的房间（与若依 component 字段同义）。
     */
    @Size(max = 64, message = "页面标识不能超过 64 字")
    @Pattern(regexp = "^$|^[a-z][a-z0-9]*$",
            message = "页面标识须为小写字母开头的标识符，如 work / forms")
    private String component;

    /** 同级排序号，越小越靠前 */
    @Min(value = 0, message = "排序号不能为负")
    private Integer sortNo;
}
