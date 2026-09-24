package com.hxj.oa.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hxj.oa.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 数据字典 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_dict")
public class SysDict extends BaseEntity {

    /** 所属公司 ID；null = 全局通用 */
    private Long companyId;
    /** 字典类型 project / seal_type / pay_type / expense_type ... */
    private String dictType;
    /** 字典项编码，存的是它，界面显示的是 dictLabel */
    private String dictCode;
    /** 字典项名称（展示用） */
    private String dictLabel;
    /** 排序号，升序 */
    private Integer sortNo;
    /** 状态 1 启用 0 停用 */
    private Integer status;
}
