package com.hxj.oa.system.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内吊销存储（{@code oa.redis.enabled=false} 时的默认实现，本机联调就是它）。
 *
 * <p>只记住"未过期"的 jti：过期 token 本来就解析不过（JWT 自带 exp），
 * 再占着内存没有意义。读取时顺手把过期的清掉，避免长期运行下缓慢堆积。
 *
 * <p>单实例语义：本机只有一个后端实例，够用。线上是 Redis 实现，
 * 蓝绿两个实例共享同一份黑名单（见 {@link RedisTokenRevocationStore}）。
 */
@Component
@ConditionalOnProperty(name = "oa.redis.enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryTokenRevocationStore implements TokenRevocationStore {

    private static final int MAX_ENTRIES = 5000;

    /** jti → 到期毫秒。之所以存到期时间而不是布尔，是为了能按时间清掉失效项。 */
    private final Map<String, Long> revokedUntil = new ConcurrentHashMap<>();

    @Override
    public void revoke(String tokenId, long ttlMillis) {
        if (tokenId == null || tokenId.isBlank() || ttlMillis <= 0) {
            return;
        }
        // 上限兜底：正常路径下每个 jti 都对应一次真实登出，量很小；
        // 真碰到上限说明被异常调用打满了，此时宁可清一遍也不让它吃掉内存
        if (revokedUntil.size() >= MAX_ENTRIES) {
            sweepExpired();
        }
        revokedUntil.put(tokenId, System.currentTimeMillis() + ttlMillis);
    }

    @Override
    public boolean isRevoked(String tokenId) {
        if (tokenId == null || tokenId.isBlank()) {
            return false;
        }
        Long until = revokedUntil.get(tokenId);
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() > until) {
            revokedUntil.remove(tokenId);
            return false;
        }
        return true;
    }

    private void sweepExpired() {
        long now = System.currentTimeMillis();
        revokedUntil.entrySet().removeIf(e -> e.getValue() != null && e.getValue() <= now);
    }
}
