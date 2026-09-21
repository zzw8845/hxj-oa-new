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

    private Long companyId;
    /** 字典类型 project / seal_type / pay_type / expense_type ... */
    private String dictType;
    private String dictCode;
    private String dictLabel;
    private Integer sortNo;
    private Integer status;
}
