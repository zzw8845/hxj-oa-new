package com.hxj.oa.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hxj.oa.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 岗位 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("post")
public class Post extends BaseEntity {

    /** 所属公司 ID */
    private Long companyId;
    /** 岗位编码，同公司内唯一 */
    private String code;
    /** 岗位名称，如 部门负责人岗 / 财务核算岗 */
    private String name;
    /** 状态 1 启用 0 停用 */
    private Integer status;
    /** 排序号，升序 */
    private Integer sortNo;
    /** 创建人 ID */
    private Long createdBy;
    /** 最后更新人 ID */
    private Long updatedBy;
}
