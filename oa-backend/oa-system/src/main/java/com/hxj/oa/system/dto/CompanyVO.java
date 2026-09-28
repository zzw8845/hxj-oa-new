package com.hxj.oa.system.dto;

import lombok.Data;

/**
 * 公司信息视图对象。
 *
 * <p>供登录后的两处展示用：左侧 logo 的公司简称、单据详情里的「所属公司」；
 * 以及公司信息维护页的回填。字段刻意精简 —— 公司表里没有对用户有意义的其他信息。
 */
@Data
public class CompanyVO {

    /** 公司 ID */
    private Long id;

    /** 公司编码（如 HXJ）：系统内部标识，用于对接外部系统时定位，一般不需要展示给用户 */
    private String code;

    /** 公司全称，如「福建海峡金投资有限公司」 */
    private String name;

    /** 公司简称，如「海峡金」—— 界面上的 logo 与列表里显示的是这个 */
    private String shortName;

    /** 1 启用 0 停用 */
    private Integer status;
}
