package com.hxj.oa.document.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 单据类型创建/编辑请求。
 *
 * <p>{@code category} 是系统级枚举（DAILY/BIZ/REIMBURSE/SEAL），前端下拉只是
 * 后端白名单的回显 —— 真正的合法性校验在 {@code DocumentTypeAdminService}，
 * 注解只做第一道格式挡板（管理员不能发明新的业务大类）。
 */
@Data
public class DocumentTypeSaveReq {

    /** 类型编码，如 PROCURE_REQ；创建后即被表单/流程/单据按此引用，只允许字母数字下划线 */
    @NotBlank(message = "类型编码不能为空")
    @Size(max = 64, message = "类型编码最长 64 字符")
    @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]*$", message = "类型编码必须是字母开头、仅含字母数字下划线")
    private String code;

    /** 类型名称 */
    @NotBlank(message = "类型名称不能为空")
    @Size(max = 64, message = "类型名称最长 64 字符")
    private String name;

    /** 业务大类：必须来自后端白名单，见 DocumentTypeAdminService#CATEGORY_LABELS */
    @NotBlank(message = "业务类型不能为空")
    private String category;

    /** 是否必须关联前置单据：1 是 0 否 */
    @Min(0) @Max(1)
    private Integer mustLinkPrev = 0;

    /** 状态：1 启用 0 停用（停用后不在发起菜单出现，历史单据不受影响） */
    @Min(0) @Max(1)
    private Integer status = 1;

    /** 排序号，小的在前 */
    private Integer sortNo = 0;
}
