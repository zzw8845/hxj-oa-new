package com.hxj.oa.system.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 部门树节点（children 递归） */
@Data
public class DeptTreeVO {

    /** 部门 ID */
    private Long id;
    /** 上级部门 ID；0 或 null = 顶级 */
    private Long parentId;
    /** 部门编码 */
    private String code;
    /** 部门名称 */
    private String name;
    /** 1 一级中心 2 二级部门（由有没有上级决定，接口不接收该参数） */
    private Integer deptType;
    /** 层级，从 1 开始 */
    private Integer level;
    /** 物化路径，形如 /8/12/ */
    private String path;
    /** 部门负责人用户 ID；「取发起人主管」的指派规则依赖它。null = 未设负责人 */
    private Long leaderId;
    /** 部门负责人姓名 */
    private String leaderName;
    /** 子部门 */
    private List<DeptTreeVO> children = new ArrayList<>();
}
