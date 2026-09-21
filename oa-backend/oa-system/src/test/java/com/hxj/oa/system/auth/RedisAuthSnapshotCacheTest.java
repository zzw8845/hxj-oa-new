package com.hxj.oa.system.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hxj.oa.common.security.DataScopeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis 快照缓存：蓝绿双实例共享失效信号的实现。
 *
 * <p>三个必须守住的点：
 * <ol>
 *   <li><b>版本号进键名</b> —— 失效只做一次 INCR，绝不能退化成扫键删除
 *       （这台 Redis 与其他项目共用，KEYS/SCAN 会阻塞整个实例）；</li>
 *   <li><b>每次写入都带 TTL</b> —— 该实例 maxmemory-policy=noeviction，
 *       漏 TTL 的后果是把同实例上别的项目写挂，不只是自己变慢；</li>
 *   <li><b>Redis 故障与脏数据都不能让登录 500</b>。</li>
 * </ol>
 */
class RedisAuthSnapshotCacheTest {

    private static final String GEN_KEY = "oa:auth:gen";
    private static final String SNAP_KEY = "oa:auth:snap:v1:0:7";

    private final ObjectMapper json = new ObjectMapper();

    private static AuthSnapshotCache.AuthSnapshot sample() {
        return new AuthSnapshotCache.AuthSnapshot(
                Set.of("EMPLOYEE"), Set.of("todo:menu"), DataScopeType.SELF, Set.of());
    }

    /** Redis 模板 + 其 value 操作的组合，方便逐个用例定制行为 */
    private record Pair(StringRedisTemplate redis, ValueOperations<String, String> ops) {
    }

    private Pair newTemplate() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        return new Pair(redis, ops);
    }

    private RedisAuthSnapshotCache cache(Pair p) {
        return new RedisAuthSnapshotCache(p.redis(), json, "oa:");
    }

    @Test
    @DisplayName("写入的键名带当前版本号，且必须带 TTL")
    void putUsesVersionedKeyWithTtl() throws Exception {
        Pair p = newTemplate();
        when(p.ops().get(GEN_KEY)).thenReturn("0");

        cache(p).put(7L, sample());

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(p.ops()).set(key.capture(), value.capture(), ttl.capture());

        assertEquals(SNAP_KEY, key.getValue(), "键名必须含版本号，否则换代失效不成立");
        assertNotNull(ttl.getValue(), "TTL 不能为 null —— noeviction 实例上漏 TTL 会写挂别人");
        assertEquals(Duration.ofMinutes(30), ttl.getValue());
        // 序列化后的内容要能反序列化回等价对象（字段名错了会在这里暴露）
        AuthSnapshotCache.AuthSnapshot back =
                json.readValue(value.getValue(), AuthSnapshotCache.AuthSnapshot.class);
        assertEquals(Set.of("EMPLOYEE"), back.roleCodes());
        assertEquals(DataScopeType.SELF, back.dataScope());
    }

    @Test
    @DisplayName("版本号键不存在时按 0 处理，与首次写入保持一致")
    void missingGenerationFallsBackToZero() {
        Pair p = newTemplate();
        when(p.ops().get(GEN_KEY)).thenReturn(null);
        when(p.ops().get(SNAP_KEY)).thenReturn(null);

        assertNull(cache(p).get(7L));
        verify(p.ops()).get(SNAP_KEY);
    }

    @Test
    @DisplayName("写出后再读回，得到等价的快照")
    void putThenGetRoundTrip() throws Exception {
        Pair p = newTemplate();
        when(p.ops().get(GEN_KEY)).thenReturn("3");
        String key = "oa:auth:snap:v1:3:7";
        when(p.ops().get(key)).thenReturn(json.writeValueAsString(sample()));

        AuthSnapshotCache.AuthSnapshot got = cache(p).get(7L);
        assertNotNull(got);
        assertEquals(Set.of("todo:menu"), got.permCodes());
    }

    @Test
    @DisplayName("失效只做一次 INCR，绝不扫键删除")
    void invalidateOnlyIncrementsGeneration() {
        Pair p = newTemplate();
        when(p.ops().increment(GEN_KEY)).thenReturn(4L);

        cache(p).invalidateAll();

        verify(p.ops()).increment(GEN_KEY);
        // 明确断言没有走"删键"这条路：KEYS/SCAN 会阻塞共用实例
        verify(p.redis(), org.mockito.Mockito.never()).keys(any());
        verify(p.redis(), org.mockito.Mockito.never()).delete(any(String.class));
    }

    @Test
    @DisplayName("脏 JSON 按未命中处理，不能让登录 500")
    void malformedJsonTreatedAsMiss() {
        Pair p = newTemplate();
        when(p.ops().get(GEN_KEY)).thenReturn("0");
        when(p.ops().get(SNAP_KEY)).thenReturn("{不是合法 JSON");

        assertNull(cache(p).get(7L));
    }

    @Test
    @DisplayName("Redis 读异常：返回未命中，不抛给调用方")
    void redisFailureOnGetDegradesToMiss() {
        Pair p = newTemplate();
        when(p.ops().get(GEN_KEY)).thenThrow(new RedisConnectionFailureException("refused"));

        assertNull(cache(p).get(7L), "缓存故障只应导致回库重建，不能中断登录");
    }

    @Test
    @DisplayName("Redis 写异常与失效异常都不能往上抛")
    void redisFailureOnWriteAndInvalidateIsSwallowed() {
        Pair p = newTemplate();
        when(p.ops().get(GEN_KEY)).thenThrow(new RedisConnectionFailureException("refused"));
        when(p.ops().increment(GEN_KEY)).thenThrow(new RedisConnectionFailureException("refused"));

        RedisAuthSnapshotCache c = cache(p);
        c.put(7L, sample());
        c.invalidateAll();
        // 没有异常逃出去即通过；本用例的价值在于"故障路径不制造新的故障"
        verify(p.ops()).increment(GEN_KEY);
    }

    @Test
    @DisplayName("非法入参直接忽略，不产生任何 Redis 访问")
    void nullArgumentsAreIgnored() {
        Pair p = newTemplate();
        RedisAuthSnapshotCache c = cache(p);

        assertNull(c.get(null));
        c.put(null, sample());
        c.put(7L, null);

        verify(p.ops(), org.mockito.Mockito.never()).set(any(String.class), any(String.class), any(Duration.class));
    }
}
