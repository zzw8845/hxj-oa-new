package com.hxj.oa.system.auth;

import java.time.Duration;

/**
 * 登录失败限速：让在线撞库付出时间成本。
 *
 * <p>此前 login 对失败请求不设任何上限，攻击者可以每秒成千次地撞
 * 「admin + 123456」这类弱口令。本接口是那道闸门。
 *
 * <p><b>为什么必须放进共享存储</b>：部署形态是<em>蓝绿双实例</em>（两个容器同时在线）。
 * 若计数只放在进程内，攻击者轮流打两个实例，就能把「10 次/5 分钟」变成
 * 「20 次/5 分钟」—— 限速的有效性直接减半，实例越多越形同虚设。
 * 所以生产用 {@link RedisLoginThrottle}，{@link InMemoryLoginThrottle} 只用于
 * 本地联调与 Redis 故障降级。
 *
 * <p><b>刻意只按账号、不按 IP</b>：加 IP 就得把请求上下文一路透传进 service，
 * 而这条路径的价值是「拖慢针对某个账号的撞库」，按账号已经达到目的。
 */
public interface LoginThrottle {

    /** 窗口内允许的最大失败次数，超过即拒绝（429） */
    int MAX_FAILURES = 10;

    /** 统计窗口；窗口外的失败自动过期，不会累积成永久封禁 */
    Duration FAILURE_WINDOW = Duration.ofMinutes(5);

    /** 超出上限时抛 429；未超限静默返回 */
    void assertAllowed(String account);

    /** 记一次失败。密码错、账号不存在都算 —— 两者返回同一句错误文案，不留账号是否存在的旁路 */
    void onFailure(String account);

    /** 登录成功：清零，避免正常用户被自己以前的手误拖累 */
    void onSuccess(String account);
}
