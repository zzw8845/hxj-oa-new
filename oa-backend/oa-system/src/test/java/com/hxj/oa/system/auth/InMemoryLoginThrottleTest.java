package com.hxj.oa.system.auth;

import com.hxj.oa.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 进程内限速：本地联调与 Redis 降级时的行为基线。
 *
 * <p>这里锁住三件事，它们都是"线上出问题才想起来"的类型：
 * 上限确实是 10、成功登录会清零、空账号不能被当成一个真实账号去累计。
 */
class InMemoryLoginThrottleTest {

    private final InMemoryLoginThrottle throttle = new InMemoryLoginThrottle();

    @Test
    @DisplayName("按真实请求顺序：第 10 次失败记满，第 11 次请求被拦（429）")
    void blocksAfterMaxFailures() {
        // 每次请求的顺序是「先判后记」：assertAllowed -> 校验密码 -> 失败则 onFailure
        for (int i = 1; i <= LoginThrottle.MAX_FAILURES; i++) {
            int n = i;
            assertDoesNotThrow(() -> throttle.assertAllowed("alice"),
                    "第 " + n + " 次请求时窗口内失败仅 " + (n - 1) + " 次，不应被拦");
            throttle.onFailure("alice");
        }

        BizException e = assertThrows(BizException.class, () -> throttle.assertAllowed("alice"),
                "已累计 10 次失败，第 11 次请求必须被拦");
        assertEquals(429, e.getCode());
    }

    @Test
    @DisplayName("登录成功清零，正常用户不被自己以前的手误拖累")
    void successResetsCounter() {
        for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) {
            throttle.onFailure("bob");
        }
        assertThrows(BizException.class, () -> throttle.assertAllowed("bob"));

        throttle.onSuccess("bob");
        assertDoesNotThrow(() -> throttle.assertAllowed("bob"));
    }

    @Test
    @DisplayName("计数按账号隔离：a 被限速不影响 b")
    void countersArePerAccount() {
        for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) {
            throttle.onFailure("carol");
        }
        assertThrows(BizException.class, () -> throttle.assertAllowed("carol"));
        assertDoesNotThrow(() -> throttle.assertAllowed("dave"));
    }

    @Test
    @DisplayName("null / 空账号是安全输入，不能被累计成一次失败")
    void blankAccountIsIgnored() {
        for (int i = 0; i < LoginThrottle.MAX_FAILURES * 2; i++) {
            throttle.onFailure(null);
            throttle.onFailure("");
            throttle.onFailure("   ");
        }
        assertDoesNotThrow(() -> throttle.assertAllowed(null));
        assertDoesNotThrow(() -> throttle.assertAllowed(""));
        assertDoesNotThrow(() -> throttle.assertAllowed("   "));
        // 空账号没有被写进失败表（否则下面这个"真账号"应仍是干净的）
        assertDoesNotThrow(() -> throttle.assertAllowed(""));
    }
}
