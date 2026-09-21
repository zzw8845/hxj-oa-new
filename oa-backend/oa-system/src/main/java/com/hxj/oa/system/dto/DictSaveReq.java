package com.hxj.oa.system.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 字典项新建 / 编辑请求。
 *
 * <p>同 {@link DeptSaveReq}：**不加 {@code @NotBlank}**。
 * 这条正是上一轮踩出来的坑：原本给 {@code dictLabel} 加了 {@code @NotBlank}，
 * 于是「PUT 只改名称」之外的所有局部编辑（例如只改排序号）都会被判 400「不能为空」，
 * 报错指向字段校验，看不出根因是"新增与局部编辑共用一个 DTO"。
 *
 * <p><b>字典的唯一性不能指望数据库索引</b>：{@code uk_dict(dict_type, dict_code,
 * company_id, deleted)} 里 {@code company_id} 允许为 NULL，而 MySQL 的唯一索引
 * **认为多个 NULL 互不相同** —— 也就是说同一个 dict_code 可以重复插进去而索引不报错。
 * 而本项目的字典恰好都是全局字典（company_id 全为 NULL）。所以唯一性必须在
 * service 里显式查一次（见 {@code OrgAdminService#assertDictFree}）。
 */
@Data
public class DictSaveReq {

    @Size(max = 64, message = "字典类型不能超过 64 字")
    private String dictType;

    @Size(max = 64, message = "字典项编码不能超过 64 字")
    private String dictCode;

    @Size(max = 128, message = "字典项名称不能超过 128 字")
    private String dictLabel;

    private Integer sortNo;

    /** 1 启用 0 停用 */
    private Integer status;
}
