package com.hxj.oa.system.auth;

import com.hxj.oa.common.security.DataScopeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 进程内快照缓存：语义必须与 Redis 版一致（TTL + 版本号失效），
 * 否则会出现"本地跑对、上线行为不同"这类最难查的差异。
 */
class InMemoryAuthSnapshotCacheTest {

    private final InMemoryAuthSnapshotCache cache = new InMemoryAuthSnapshotCache();

    private static AuthSnapshotCache.AuthSnapshot sample(String role) {
        return new AuthSnapshotCache.AuthSnapshot(
                Set.of(role), Set.of(role + ":menu"), DataScopeType.DEPT, Set.of(7L, 8L));
    }

    @Test
    @DisplayName("写入后可读到同一份快照")
    void putThenGet() {
        cache.put(1L, sample("EMPLOYEE"));
        AuthSnapshotCache.AuthSnapshot got = cache.get(1L);
        assertNotNull(got);
        assertEquals(Set.of("EMPLOYEE"), got.roleCodes());
        assertEquals(DataScopeType.DEPT, got.dataScope());
        assertEquals(Set.of(7L, 8L), got.scopeDeptIds());
    }

    @Test
    @DisplayName("未命中与非法入参都返回 null，而不是抛异常")
    void missReturnsNull() {
        assertNull(cache.get(null));
        assertNull(cache.get(999L));
        cache.put(null, sample("X"));
        cache.put(2L, null);
        assertNull(cache.get(2L));
    }

    @Test
    @DisplayName("invalidateAll 后旧快照立即不可读（版本号换代）")
    void invalidateMakesOldEntriesUnreachable() {
        cache.put(1L, sample("ADMIN"));
        assertNotNull(cache.get(1L));

        cache.invalidateAll();
        assertNull(cache.get(1L), "换代后必须读不到旧快照，否则改角色权限不会生效");

        cache.put(1L, sample("ADMIN"));
        assertNotNull(cache.get(1L), "失效之后写入的新快照应可正常读到");
    }

    @Test
    @DisplayName("无事务上下文时 invalidateAfterCommit 立即失效")
    void invalidateAfterCommitWithoutTransactionIsImmediate() {
        cache.put(1L, sample("ADMIN"));
        cache.invalidateAfterCommit();
        assertNull(cache.get(1L));
    }

    @Test
    @DisplayName("有事务上下文时失效被推迟到提交之后，回滚则缓存保持原样")
    void invalidateAfterCommitDefersToCommit() {
        // 这段模拟 Spring 事务同步：注册回调但不触发，等价于"事务还没提交"
        TransactionSynchronizationManager.initSynchronization();
        try {
            cache.put(1L, sample("ADMIN"));
            cache.invalidateAfterCommit();
            assertNotNull(cache.get(1L), "提交前不应失效 —— 回滚时缓存必须仍是有效的");

            // 提交：手动跑一遍 afterCommit 回调
            for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
                s.afterCommit();
            }
            assertNull(cache.get(1L), "提交后必须失效");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("TTL 到期的条目按未命中处理（版本号没变也不会返回陈旧数据）")
    void expiredEntryIsEvicted() throws Exception {
        cache.put(1L, sample("ADMIN"));
        assertNotNull(cache.get(1L));

        // 真等 30 分钟不现实，直接把缓存里那条记录换成"已过期"的同代号条目，
        // 这样只隔离出 TTL 这一条判据（版本号保持不变）。
        var entriesField = InMemoryAuthSnapshotCache.class.getDeclaredField("entries");
        entriesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var map = (java.util.Map<Long, Object>) entriesField.get(cache);

        Object live = map.get(1L);
        var entryClass = live.getClass();
        var generationField = entryClass.getDeclaredField("generation");
        generationField.setAccessible(true);
        long sameGeneration = (long) generationField.get(live);

        var canonical = entryClass.getDeclaredConstructor(long.class, long.class,
                AuthSnapshotCache.AuthSnapshot.class);
        canonical.setAccessible(true);
        map.put(1L, canonical.newInstance(sameGeneration, System.currentTimeMillis() - 1, sample("ADMIN")));

        assertNull(cache.get(1L), "已过期条目应被剔除");
    }
}
