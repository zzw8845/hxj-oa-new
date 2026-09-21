package com.hxj.oa.system.auth;

import com.hxj.oa.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 基于 Redis 计数的登录限速 —— 蓝绿双实例下的正确实现。
 *
 * <p>计数键：{@code <prefix>login:fail:<account>}，值为窗口内失败次数，首次失败时设置 TTL。
 * 用 {@code INCR} 而不是"读出来加一写回去"：后者在并发撞库下会丢计数，
 * 而丢计数恰恰发生在最需要它准确的时候。
 *
 * <p><b>两处刻意的兜底设计</b>：
 * <ol>
 *   <li><b>TTL 自愈</b>：{@code INCR} 与 {@code EXPIRE} 是两条命令，
 *       若进程恰在两者之间被杀，键会变成"永不过期"，那个账号就<em>再也登不进来</em>。
 *       所以判定时顺带查 TTL，发现没有就补设 —— 把永久锁死降级成 5 分钟。
 *       这类"只在异常时序下出现、一旦出现就是硬故障"的坑，必须主动兜。</li>
 *   <li><b>降级到进程内</b>：Redis 异常时不放行、也不把所有人挡在外面，
 *       而是退回 {@link InMemoryLoginThrottle}（每实例 10 次）。
 *       本地计数在每次失败时同步写入，所以降级后立刻有据可依，不是从零开始。</li>
 * </ol>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "oa.redis.enabled", havingValue = "true")
public class RedisLoginThrottle implements LoginThrottle {

    private static final String KEY_PREFIX = "login:fail:";

    private final StringRedisTemplate redis;
    private final String keyPrefix;
    private final DegradeLog degrade = new DegradeLog("登录限速");

    /**
     * Redis 故障时的降级目标。这里直接 new 而不是注入：
     * 两个实现的 {@code @ConditionalOnProperty} 条件互斥，同时只能有一个存在于容器中。
     */
    private final InMemoryLoginThrottle fallback = new InMemoryLoginThrottle();

    public RedisLoginThrottle(StringRedisTemplate redis,
                              @Value("${oa.redis.key-prefix:oa:}") String keyPrefix) {
        this.redis = redis;
        this.keyPrefix = keyPrefix;
    }

    @Override
    public void assertAllowed(String account) {
        if (account == null || account.isBlank()) {
            return;
        }
        String key = key(account);
        try {
            String raw = redis.opsForValue().get(key);
            if (raw == null) {
                return;
            }
            long failures = Long.parseLong(raw);
            if (failures < MAX_FAILURES) {
                return;
            }
            repairTtlIfMissing(key);
            log.warn("登录被限速（Redis 计数） account={} 窗口内失败={} 次", account, failures);
            throw new BizException(429, "登录失败次数过多，请 5 分钟后再试");
        } catch (BizException e) {
            // 429 是我们自己抛的业务异常，必须原样透出，不能被下面的 catch 吃掉
            throw e;
        } catch (RuntimeException e) {
            degrade.warn("限速判定", e);
            fallback.assertAllowed(account);
        }
    }

    @Override
    public void onFailure(String account) {
        if (account == null || account.isBlank()) {
            return;
        }
        // 无论 Redis 是否可用都记一份本地计数：降级时立即生效，不必等下一次失败
        fallback.onFailure(account);
        String key = key(account);
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redis.expire(key, FAILURE_WINDOW);
            }
        } catch (RuntimeException e) {
            degrade.warn("失败计数", e);
        }
    }

    @Override
    public void onSuccess(String account) {
        if (account == null) {
            return;
        }
        fallback.onSuccess(account);
        try {
            redis.delete(key(account));
        } catch (RuntimeException e) {
            degrade.warn("失败计数清零", e);
        }
    }

    /** 键没有 TTL（getExpire 返回 -1）时补设，防止 INCR 成功而 EXPIRE 未执行导致的永久封禁 */
    private void repairTtlIfMissing(String key) {
        Long ttlSeconds = redis.getExpire(key);
        if (ttlSeconds != null && ttlSeconds < 0) {
            redis.expire(key, FAILURE_WINDOW);
            log.warn("登录限速键缺少 TTL，已补设 {} 分钟：key={}", FAILURE_WINDOW.toMinutes(), key);
        }
    }

    /**
     * 账号直接拼进键名。Redis 的键允许任意字节，且我们只做精确键读写
     * （不存在按模式匹配的场景），因此没有注入面；trim 只是挡掉表单里的前后空格。
     */
    private String key(String account) {
        return keyPrefix + KEY_PREFIX + account.trim();
    }
}
