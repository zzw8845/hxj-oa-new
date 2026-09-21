package com.hxj.oa.system.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内快照缓存（默认实现，{@code oa.redis.enabled=false}）。
 *
 * <p>语义与 {@link RedisAuthSnapshotCache} 刻意保持一致（TTL + 版本号失效），
 * 这样"本地跑对、线上跑错"这类环境差异不会因为缓存行为不同而出现。
 *
 * <p>容量策略是"超上限就不缓存"而不是 LRU 淘汰：OA 的登录用户数量是几十到几百的量级，
 * 2000 条上限在任何真实场景下都碰不到；真碰到了说明公司规模已经变了，
 * 那时的正确动作是把 Redis 打开，而不是让本地缓存去做淘汰。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "oa.redis.enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryAuthSnapshotCache implements AuthSnapshotCache {

    /** TTL 只用于限制内存占用；一致性由版本号保证，不依赖它 */
    private static final Duration TTL = Duration.ofMinutes(30);
    private static final int MAX_ENTRIES = 2000;

    private final AtomicLong generation = new AtomicLong(0L);
    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();

    private record Entry(long generation, long expireAtMillis, AuthSnapshot snapshot) {
    }

    @Override
    public AuthSnapshot get(Long userId) {
        if (userId == null) {
            return null;
        }
        Entry e = entries.get(userId);
        if (e == null) {
            return null;
        }
        if (e.generation() != generation.get() || e.expireAtMillis() < System.currentTimeMillis()) {
            entries.remove(userId, e);
            return null;
        }
        return e.snapshot();
    }

    @Override
    public void put(Long userId, AuthSnapshot snapshot) {
        if (userId == null || snapshot == null || entries.size() >= MAX_ENTRIES) {
            return;
        }
        entries.put(userId, new Entry(generation.get(),
                System.currentTimeMillis() + TTL.toMillis(), snapshot));
    }

    @Override
    public void invalidateAll() {
        long g = generation.incrementAndGet();
        entries.clear();
        log.info("认证快照缓存已失效（进程内），新版本号={}", g);
    }
}
