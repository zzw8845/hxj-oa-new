package com.hxj.oa.system.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis 吊销存储 —— 蓝绿双实例共享的登出信号。
 *
 * <p><b>键设计</b>：{@code <prefix>auth:revoked:<jti>}，值为固定字符串 {@code "1"}。
 * 只记 jti 不记 userId（理由见 {@link TokenRevocationStore}）。
 *
 * <p><b>每条写入都必须带 TTL，没有例外</b>：这台 Redis 的 {@code maxmemory=0} 且
 * {@code maxmemory-policy=noeviction} —— 内存写满时它不会淘汰任何键，而是直接对写入报错。
 * 所以漏掉 TTL 的后果不是自己变慢，是**把同实例上的其他项目写挂**。
 * TTL 取 token 的剩余寿命：过期后它本来就用不了了，没理由继续占着。
 *
 * <h3>为什么 Redis 故障时「失败开放」</h3>
 * 吊销查询在受保护请求的必经路径上。若 Redis 一挂就把所有请求判成 401，
 * 等于把缓存故障放大成**全站不可用** —— 而登出吊销的收益（共用电脑上少一个 12 小时窗口）
 * 远小于全站不可用的代价。
 * 所以：查不到吊销状态时当作"未被吊销"，放行，同时打一条限流后的降级 warn
 * （{@link DegradeLog}，避免 Redis 挂掉时把日志刷爆）。
 * 这条取舍的代价要写清楚：**Redis 不可用期间，登出的 token 会暂时仍能使用。**
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "oa.redis.enabled", havingValue = "true")
public class RedisTokenRevocationStore implements TokenRevocationStore {

    private static final String KEY_PREFIX_PART = "auth:revoked:";
    /** 兜底上限：即便调用方算错了剩余寿命，也不至于把键一直留在这台共享 Redis 上 */
    private static final Duration MAX_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;
    private final String keyPrefix;
    private final DegradeLog degrade = new DegradeLog("登出吊销");

    public RedisTokenRevocationStore(StringRedisTemplate redis,
                                     @Value("${oa.redis.key-prefix:oa:}") String keyPrefix) {
        this.redis = redis;
        this.keyPrefix = keyPrefix;
    }

    @Override
    public void revoke(String tokenId, long ttlMillis) {
        if (tokenId == null || tokenId.isBlank() || ttlMillis <= 0) {
            return;
        }
        Duration ttl = Duration.ofMillis(Math.min(ttlMillis, MAX_TTL.toMillis()));
        try {
            redis.opsForValue().set(keyPrefix + KEY_PREFIX_PART + tokenId, "1", ttl);
        } catch (RuntimeException e) {
            degrade.warn("吊销记录写入", e);
        }
    }

    @Override
    public boolean isRevoked(String tokenId) {
        if (tokenId == null || tokenId.isBlank()) {
            return false;
        }
        try {
            Boolean hit = redis.hasKey(keyPrefix + KEY_PREFIX_PART + tokenId);
            return Boolean.TRUE.equals(hit);
        } catch (RuntimeException e) {
            // 失败开放：见类注释。宁可让一张已登出的 token 多活一会儿，
            // 也不让 Redis 故障变成全站 401。
            degrade.warn("吊销查询", e);
            return false;
        }
    }
}
