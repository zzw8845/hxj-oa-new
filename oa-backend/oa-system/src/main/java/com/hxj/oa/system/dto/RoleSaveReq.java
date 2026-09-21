package com.hxj.oa.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 角色新建 / 编辑请求。字段语义同 {@link UserSaveReq}：ID 优先、名称兜底 */
@Data
public class RoleSaveReq {

    @NotBlank(message = "角色名称不能为空")
    @Size(max = 64, message = "角色名称不能超过 64 字")
    private String name;

    /** 角色编码。留空则由后端按 CUSTOM_nn 规则生成 */
    @Size(max = 64, message = "角色编码不能超过 64 字")
    private String code;

    private Long deptId;
    private String deptName;

    @Size(max = 64, message = "岗位名称不能超过 64 字")
    private String postName;

    /** 数据范围：self / dept / center / custom_dept / company，留空默认 self */
    private String scopeType;

    /** custom_dept 时生效的部门集合 */
    private List<Long> scopeDeptIds;

    /** 权限点编码全量覆盖 */
    private List<String> permCodes;

    @Size(max = 255, message = "备注不能超过 255 字")
    private String remark;
}
