package com.hxj.oa.system.auth;

import com.hxj.oa.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内限速实现（原 {@code AuthService} 里的逻辑原样搬过来，行为不变）。
 *
 * <p>默认启用（{@code oa.redis.enabled=false}）：本地联调零外部依赖即可跑通。
 *
 * <p><b>它是"单实例限定"的</b>（见 {@link LoginThrottle}），因此除了本地联调，
 * 它还是 {@link RedisLoginThrottle} 在 Redis 异常时的<em>降级目标</em>：
 * Redis 挂了不代表放弃限速，只是从"全局 10 次"退化成"每实例 10 次"——
 * 比直接放行强，也不会因为缓存故障把所有人挡在系统外。
 *
 * <p>注意：那个 {@link RedisLoginThrottle} 里用的是 {@code new} 出来的实例，
 * 不是注入的 Bean —— 两个实现的条件互斥，不可能同时注册进容器。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "oa.redis.enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryLoginThrottle implements LoginThrottle {

    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    @Override
    public void assertAllowed(String account) {
        if (account == null || account.isBlank()) {
            return;
        }
        Deque<Instant> times = failures.get(account);
        if (times == null) {
            return;
        }
        synchronized (times) {
            prune(times);
            if (times.size() >= MAX_FAILURES) {
                log.warn("登录被限速（进程内计数） account={} 窗口内失败={} 次", account, times.size());
                throw new BizException(429, "登录失败次数过多，请 5 分钟后再试");
            }
        }
    }

    @Override
    public void onFailure(String account) {
        if (account == null || account.isBlank()) {
            return;
        }
        Deque<Instant> times = failures.computeIfAbsent(account, k -> new ArrayDeque<>());
        synchronized (times) {
            // 顺手 prune：否则一个只在很久以前失败过的账号，会把它那几条早已过期的
            // 时间戳一直留在堆里，长期运行下这是可累积的内存泄漏
            prune(times);
            times.addLast(Instant.now());
        }
    }

    @Override
    public void onSuccess(String account) {
        if (account != null) {
            failures.remove(account);
        }
    }

    /** 丢弃窗口外的失败时间戳（调用方必须持有 times 的锁） */
    private void prune(Deque<Instant> times) {
        Instant cutoff = Instant.now().minus(FAILURE_WINDOW);
        while (!times.isEmpty() && times.peekFirst().isBefore(cutoff)) {
            times.pollFirst();
        }
    }
}
