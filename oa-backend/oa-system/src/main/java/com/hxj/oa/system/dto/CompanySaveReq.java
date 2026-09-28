package com.hxj.oa.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改公司信息的请求体。
 *
 * <p>只开放两个字段：名称与简称。编码（code）不开放 —— 它被用来拼流程定义 KEY 等
 * 内部标识，改了会牵动存量的流程与单据，不是"编辑公司信息"这个动作该干的事。
 */
@Data
public class CompanySaveReq {

    /** 公司全称，必填 */
    @NotBlank(message = "公司名称不能为空")
    @Size(max = 64, message = "公司名称不能超过 64 字")
    private String name;

    /** 公司简称：留空则前端回退显示全称 */
    @Size(max = 32, message = "公司简称不能超过 32 字")
    private String shortName;
}
