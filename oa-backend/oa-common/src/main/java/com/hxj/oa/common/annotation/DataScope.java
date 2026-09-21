package com.hxj.oa.common.annotation;

import java.lang.annotation.*;

/**
 * 标记一个查询需要按当前用户的角色数据范围做行级过滤。
 * 由 DataScopeHelper 生成 SQL 片段，服务层显式拼进 QueryWrapper。
 *
 * 注意：本系统用 MySQL，没有 PostgreSQL RLS 那样的数据库层兜底，
 *      所以「所有单据类查询必须显式声明本注解」是硬纪律（见报告 7.4 / 10.5）。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DataScope {

    /** 表别名，如 d（document 表） */
    String alias() default "d";

    /** 申请人字段名，SELF 范围时使用 */
    String applicantField() default "applicant_id";

    /** 部门字段名，DEPT 范围时使用 */
    String deptField() default "dept_id";

    /** 公司字段名，用于强制多公司隔离 */
    String companyField() default "company_id";
}
