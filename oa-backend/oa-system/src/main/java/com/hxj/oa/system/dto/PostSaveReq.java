package com.hxj.oa.system.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 岗位新建 / 编辑请求。
 *
 * <p>同 {@link DeptSaveReq}：**不加 {@code @NotBlank}**，必填校验在 service 的新建分支里做，
 * 以免「局部编辑只传一个字段」被判 400。
 *
 * <p>岗位表有唯一键 {@code uk_post_company_code(company_id, code, deleted)} ——
 * 注意 deleted 也在键里，所以逻辑删除一个岗位前必须先让编码让位
 * （{@code UniqueKeys.release}），否则"建 → 删 → 再建 → 再删"第二次删除会撞键报 500。
 */
@Data
public class PostSaveReq {

    @Size(max = 64, message = "岗位名称不能超过 64 字")
    private String name;

    /** 岗位编码。新建时留空由后端按 P0xx 规则生成 */
    @Size(max = 64, message = "岗位编码不能超过 64 字")
    private String code;

    private Integer sortNo;

    /** 1 启用 0 停用 */
    private Integer status;
}
