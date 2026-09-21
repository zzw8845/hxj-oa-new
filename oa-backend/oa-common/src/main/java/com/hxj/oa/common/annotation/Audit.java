package com.hxj.oa.common.annotation;

import java.lang.annotation.*;

/**
 * 标记一个写接口需要留审计痕迹。
 *
 * <p>由 {@code AuditAspect} 拦截并落库到 {@code audit_log}。设计取舍：
 * <ul>
 *   <li><b>只标写接口，不标读接口</b>。读操作量大且不留痕，全量拦截只会把日志表冲垮。</li>
 *   <li><b>显式标注而非自动按 HTTP 方法拦截</b>。<br>
 *       自动拦截会把 {@code POST /api/forms/validate}（纯校验）、{@code /api/flows/configs/{id}/preview}
 *       （纯计算）、{@code /api/notifications/{id}/read}（高频）也算进来 —— 它们是 POST 只是因为要带请求体，
 *       语义上是读。显式标注同时也逼着作者想清楚 module/action 怎么写。</li>
 *   <li><b>审计写入失败不得影响业务</b>：切面内部吞掉异常并记 error 日志，绝不向上抛。</li>
 *   <li><b>审计行独立事务提交</b>（REQUIRES_NEW）：业务事务回滚时审计行必须留下 ——
 *       「谁尝试做过什么」恰恰是失败场景下最需要的信息。</li>
 * </ul>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Audit {

    /** 模块：document / flow / permission / seal / auth */
    String module();

    /** 动作：create / update / delete / submit / approve / reject / withdraw / deploy / config */
    String action();
}
