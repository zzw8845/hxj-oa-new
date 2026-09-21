package com.hxj.oa.system.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 部门树节点 */
@Data
public class DeptTreeVO {

    private Long id;
    private Long parentId;
    private String code;
    private String name;
    private Integer deptType;
    private Integer level;
    private String path;
    private Long leaderId;
    private String leaderName;
    private List<DeptTreeVO> children = new ArrayList<>();
}
