package com.hxj.oa.system.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 部门新建 / 编辑请求。
 *
 * <p><b>刻意不加 {@code @NotBlank}</b>：同一个 DTO 同时服务「新建」和「局部编辑」，
 * 编辑时前端只会传要改的那几个字段。上一轮给字典的 DTO 加了 {@code @NotBlank}，
 * 结果「PUT 只改名称」这种完全正常的请求被判 400「不能为空」，而报错信息
 * 指向的是字段校验、看不出是"新增/编辑共用一个 DTO"导致的。
 * 所以必填校验一律放在 service 的**新建分支**里做，不靠 DTO 注解。
 *
 * <p>{@code parentId} 与 {@code code} 只在新建立时生效，编辑时传了会被明确拒绝
 * （见 {@code OrgAdminService}：刻意不做部门移动、不改编码，理由写在那里）。
 */
@Data
public class DeptSaveReq {

    @Size(max = 64, message = "部门名称不能超过 64 字")
    private String name;

    /** 部门编码。新建时留空由后端按 D0xx 规则生成 */
    @Size(max = 64, message = "部门编码不能超过 64 字")
    private String code;

    /** 上级部门 ID。null 或 0 = 顶级（一级中心）；新建时生效，编辑时不允许改 */
    private Long parentId;

    /** 部门负责人用户 ID，可空。「取发起人主管」的指派规则依赖它 */
    private Long leaderId;

    private Integer sortNo;

    /** 1 启用 0 停用 */
    private Integer status;
}
