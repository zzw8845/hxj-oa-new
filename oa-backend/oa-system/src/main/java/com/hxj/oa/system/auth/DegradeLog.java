package com.hxj.oa.system.auth;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis 降级告警的限流器（包内共用）。
 *
 * <p>为什么需要它：Redis 一旦不可用，<b>每个登录请求</b>都会走到降级分支。
 * 若每次都打一行 warn，「Redis 挂了」这一条会把有意义的日志冲掉 ——
 * 排查时看到的是刷屏的同一句话，真正的首个异常反而被埋了。
 * 所以按固定间隔只打一次，并顺带在 debug 级留下完整堆栈。
 *
 * <p>用 CAS 而不是单纯的"读比较再写"：多个请求线程同时踩到降级时，
 * 只有抢到时间戳的那一个打日志，其余静默走降级逻辑。
 */
@Slf4j
final class DegradeLog {

    private static final long INTERVAL_MS = 60_000L;

    private final String component;
    private final AtomicLong lastWarnAt = new AtomicLong(0L);

    DegradeLog(String component) {
        this.component = component;
    }

    void warn(String operation, Throwable e) {
        long now = System.currentTimeMillis();
        long last = lastWarnAt.get();
        if (now - last >= INTERVAL_MS && lastWarnAt.compareAndSet(last, now)) {
            // 只打异常摘要：堆栈交给 debug，避免把日志按秒级刷爆
            log.warn("[Redis 降级] {} 的「{}」失败，本次及此后 1 分钟内的同类操作改用进程内实现：{}",
                    component, operation, e.toString());
            log.debug("[Redis 降级] 完整堆栈 —— {} 的「{}」", component, operation, e);
        }
    }
}
