package com.hxj.oa.system.auth;

import com.hxj.oa.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis 限速：蓝绿双实例下唯一正确的实现。
 *
 * <p>这里专门覆盖三条"只有出错时才会走到"的分支 —— 它们恰恰是最容易写错、
 * 也最不可能被手工冒烟测到的部分：
 * <ol>
 *   <li>TTL 自愈：{@code INCR} 成功而 {@code EXPIRE} 未执行时，键会永不过期 → 该账号永久登不进来；</li>
 *   <li>降级不放行：Redis 挂掉时必须退回进程内计数继续拦，而不是"查不到就当没失败"；</li>
 *   <li>业务异常穿透：我们自己抛的 429 不能被降级的 catch 吃掉变成 200。</li>
 * </ol>
 */
class RedisLoginThrottleTest {

    private static final String KEY = "oa:login:fail:alice";

    /** 造一个"Redis 可用"的模板：读到的失败次数由调用方指定 */
    private static StringRedisTemplate templateReturning(String rawCount) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(KEY)).thenReturn(rawCount);
        return redis;
    }

    private static RedisLoginThrottle throttle(StringRedisTemplate redis) {
        return new RedisLoginThrottle(redis, "oa:");
    }

    @Test
    @DisplayName("计数未达上限：放行")
    void belowLimitPasses() {
        assertDoesNotThrow(() -> throttle(templateReturning("9")).assertAllowed("alice"));
    }

    @Test
    @DisplayName("计数达上限时抛 429，并且业务异常必须原样透出")
    void atLimitThrows429() {
        StringRedisTemplate redis = templateReturning("10");
        when(redis.getExpire(KEY)).thenReturn(180L); // TTL 正常，不需要补

        BizException e = assertThrows(BizException.class, () -> throttle(redis).assertAllowed("alice"));
        assertEquals(429, e.getCode());
        verify(redis, never()).expire(eq(KEY), any(Duration.class));
    }

    @Test
    @DisplayName("键没有 TTL（getExpire=-1）时补设 5 分钟，把永久封禁降级成 5 分钟")
    void repairsMissingTtl() {
        StringRedisTemplate redis = templateReturning("10");
        when(redis.getExpire(KEY)).thenReturn(-1L);

        assertThrows(BizException.class, () -> throttle(redis).assertAllowed("alice"));
        verify(redis).expire(KEY, LoginThrottle.FAILURE_WINDOW);
    }

    @Test
    @DisplayName("首次失败才设 TTL，后续失败不重置窗口（否则窗口永远不会到期）")
    void setsTtlOnlyOnFirstFailure() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);

        when(ops.increment(KEY)).thenReturn(1L);
        throttle(redis).onFailure("alice");
        verify(redis).expire(KEY, LoginThrottle.FAILURE_WINDOW);

        // 第二次失败：计数返回 2，不应再设 TTL（重置 TTL 等于把窗口无限延长）
        when(ops.increment(KEY)).thenReturn(2L);
        throttle(redis).onFailure("alice");
        verify(redis, org.mockito.Mockito.times(1)).expire(eq(KEY), any(Duration.class));
    }

    @Test
    @DisplayName("Redis 读异常：降级到进程内计数，仍然拦住第 11 次而不是放行")
    void degradesToInMemoryWhenRedisFails() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(KEY)).thenThrow(new RedisConnectionFailureException("connection refused"));
        when(ops.increment(KEY)).thenThrow(new RedisConnectionFailureException("connection refused"));

        RedisLoginThrottle t = throttle(redis);

        // Redis 全挂：这 10 次失败只能落到本地计数
        for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) {
            t.onFailure("alice");
        }
        BizException e = assertThrows(BizException.class, () -> t.assertAllowed("alice"),
                "Redis 故障不能等于放弃限速");
        assertEquals(429, e.getCode());
    }

    @Test
    @DisplayName("Redis 正常时 onSuccess 清除计数键")
    void successClearsKey() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);

        throttle(redis).onSuccess("alice");
        verify(redis).delete(KEY);
    }

    @Test
    @DisplayName("Redis 删除失败不能把登录成功变成 500")
    void successSwallowsRedisFailure() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.delete(KEY)).thenThrow(new RedisConnectionFailureException("boom"));

        assertDoesNotThrow(() -> throttle(redis).onSuccess("alice"));
    }
}
