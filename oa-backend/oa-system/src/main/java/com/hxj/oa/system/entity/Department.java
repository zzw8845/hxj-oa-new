package com.hxj.oa.system.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hxj.oa.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 部门（中心 → 二级部门） */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("department")
public class Department extends BaseEntity {

    /** 所属公司 ID */
    private Long companyId;
    /** 上级部门 ID，0 = 顶级 */
    private Long parentId;
    /** 部门编码，同公司内唯一；留空由后端按 D0xx 规则生成 */
    private String code;
    /** 部门名称，同公司内唯一 */
    private String name;
    /** 1 一级中心 2 二级部门 */
    private Integer deptType;
    /** 层级深度，从 1 开始。level 在部分 SQL 方言中是关键字，故加反引号 */
    @TableField("`level`")
    private Integer level;
    /** 物化路径，如 /2/3/ */
    private String path;
    /** 部门负责人用户 ID —— 「取发起人主管」规则依赖此字段 */
    private Long leaderId;
    /** 状态 1 启用 0 停用 */
    private Integer status;
    /** 排序号，同级内升序 */
    private Integer sortNo;
    /** 创建人 ID */
    private Long createdBy;
    /** 最后更新人 ID */
    private Long updatedBy;
}
