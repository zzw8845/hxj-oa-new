package com.hxj.oa.system.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis 快照缓存 —— 蓝绿双实例共享的失效信号。
 *
 * <p>键设计：
 * <pre>
 *   版本号（全局）  &lt;prefix&gt;auth:gen
 *   快照（按用户）  &lt;prefix&gt;auth:snap:v1:&lt;gen&gt;:&lt;userId&gt;
 * </pre>
 * 把版本号编进键名，而不是逐个删除旧键：{@code invalidateAll} 只需一次 {@code INCR}，
 * 旧键靠 TTL 自然消失。既是 O(1) 的失效，也彻底不用 {@code KEYS}/{@code SCAN} ——
 * 那两条命令在大 key 空间上会阻塞整个 Redis 实例，而这台 Redis 是与其他项目共用的，
 * 我们不能因为清自己的缓存把别人的服务卡住。
 *
 * <p><b>键名里的 {@code v1} 不是装饰</b>：蓝绿切换期间新旧两个容器会同时运行，
 * 若新版本改了 {@link AuthSnapshot} 的字段，旧容器写下的 JSON 会被新容器读到。
 * 带上版本号，两个版本各读各的键、互不干扰，升级期间不会出现反序列化失败。
 *
 * <p><b>每条写入都必须带 TTL</b>：这台 Redis 的 {@code maxmemory=0} 且
 * {@code maxmemory-policy=noeviction} —— 内存写满时它<b>不会淘汰任何键，而是直接对写入报错</b>。
 * 也就是说，我们漏掉 TTL 的后果不是自己变慢，是把同实例上的其他项目写挂。
 * 所以 {@code set(...)} 一律带上过期时间，没有例外。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "oa.redis.enabled", havingValue = "true")
public class RedisAuthSnapshotCache implements AuthSnapshotCache {

    private static final String GENERATION_KEY = "auth:gen";
    private static final String SNAPSHOT_KEY_PREFIX = "auth:snap:v1:";

    /** TTL 只用于限制内存占用；一致性靠版本号，不依赖它 */
    private static final Duration TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final String keyPrefix;
    private final DegradeLog degrade = new DegradeLog("认证快照缓存");

    /** Redis 故障时的降级目标（两个实现条件互斥，只能 new，不能注入） */
    private final InMemoryAuthSnapshotCache fallback = new InMemoryAuthSnapshotCache();

    public RedisAuthSnapshotCache(StringRedisTemplate redis,
                                  ObjectMapper objectMapper,
                                  @Value("${oa.redis.key-prefix:oa:}") String keyPrefix) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.keyPrefix = keyPrefix;
    }

    @Override
    public AuthSnapshot get(Long userId) {
        if (userId == null) {
            return null;
        }
        try {
            String version = currentVersion();
            String key = snapshotKey(version, userId);
            String json = redis.opsForValue().get(key);
            if (json == null) {
                return null;
            }
            return objectMapper.readValue(json, AuthSnapshot.class);
        } catch (JsonProcessingException e) {
            // 反序列化失败 = 缓存内容不可用，绝不能让登录因此 500。
            // 当作未命中，回库里重建即可；Redis 本身是健康的，所以不算降级、不打 warn。
            // （正常路径下这个分支不该被走到，因为键名里带了 v1。）
            log.debug("认证快照反序列化失败，按未命中处理：userId={} 原因={}", userId, e.getMessage());
            return null;
        } catch (RuntimeException e) {
            degrade.warn("快照读取", e);
            return fallback.get(userId);
        }
    }

    @Override
    public void put(Long userId, AuthSnapshot snapshot) {
        if (userId == null || snapshot == null) {
            return;
        }
        try {
            String key = snapshotKey(currentVersion(), userId);
            redis.opsForValue().set(key, objectMapper.writeValueAsString(snapshot), TTL);
        } catch (JsonProcessingException e) {
            // 写不进去只是少了次优化，不影响本次登录（返回值已在内存里）—— 静默忽略
            log.debug("认证快照序列化失败，跳过缓存：userId={} 原因={}", userId, e.getMessage());
        } catch (RuntimeException e) {
            degrade.warn("快照写入", e);
        }
    }

    @Override
    public void invalidateAll() {
        fallback.invalidateAll();
        try {
            Long version = redis.opsForValue().increment(keyPrefix + GENERATION_KEY);
            log.info("认证快照缓存已失效（Redis 换代），新版本号={}", version);
        } catch (RuntimeException e) {
            degrade.warn("缓存失效", e);
        }
    }

    /** 版本号读不到（Redis 里还没这个键）就按 0 处理，与首次写入保持一致 */
    private String currentVersion() {
        String v = redis.opsForValue().get(keyPrefix + GENERATION_KEY);
        return v == null ? "0" : v;
    }

    private String snapshotKey(String version, Long userId) {
        return keyPrefix + SNAPSHOT_KEY_PREFIX + version + ":" + userId;
    }
}
