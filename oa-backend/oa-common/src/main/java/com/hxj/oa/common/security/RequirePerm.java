package com.hxj.oa.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口级权限点校验注解。
 *
 * <p>由 {@code PermInterceptor} 读取，判定依据是 JWT 里已经携带的
 * {@link LoginUser#getPermCodes()}，因此校验过程不回查数据库。
 *
 * <p><b>重要取舍</b>：权限点是「登录时刻的快照」。管理员修改某角色的权限后，
 * 该角色下**已登录**用户的 token 不会自动更新，需重新登录（或等 token 过期）才生效。
 * 这是 {@code JwtUtils} 把完整授权信息编进 token、换取「每请求零查库」的必然代价。
 * 角色权限保存接口会在响应中明确提示这一点。
 *
 * <p>用法：
 * <pre>
 * &#64;RequirePerm("system:user")                       // 需要用户管理权限
 * &#64;RequirePerm({"system:role", "admin:menu"})        // 需要两者同时具备（默认 AND）
 * &#64;RequirePerm(value = {"system:user", "system:role"}, logic = Logic.OR)  // 任一即可
 * </pre>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequirePerm {

    /** 需要的权限点编码，如 {@code system:role} */
    String[] value();

    /** 多个权限点之间的组合方式，默认全部具备 */
    Logic logic() default Logic.AND;

    enum Logic {
        /** 必须同时具备 value 中的全部权限点 */
        AND,
        /** 具备其中任意一个即可 */
        OR
    }
}
