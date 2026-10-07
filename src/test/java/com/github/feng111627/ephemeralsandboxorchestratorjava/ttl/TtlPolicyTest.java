package com.github.feng111627.ephemeralsandboxorchestratorjava.ttl;

import com.github.feng111627.ephemeralsandboxorchestratorjava.domain.SandboxErrorCode;
import com.github.feng111627.ephemeralsandboxorchestratorjava.exception.SandboxException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 业务覆盖：有效期策略 —— 申请 TTL 正负/上限边界、续期绝对上限边界（可延长/贴边/越界）、
 * 已过期后续期从当前时刻起算。
 */
class TtlPolicyTest {

    private static final Logger log = LoggerFactory.getLogger(TtlPolicyTest.class);

    /** 上限 60 秒，便于精确覆盖边界 */
    private TtlPolicy policy;
    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-10-07T10:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp(TestInfo info) {
        policy = new TtlPolicy(60);
        log.info("[业务: 有效期] 执行用例: {}", info.getDisplayName());
    }

    @Test
    @DisplayName("申请边界: 1 秒与等于上限 60 秒合法")
    void ttlWithinMaxAccepted() {
        assertDoesNotThrow(() -> policy.validateTtl(Duration.ofSeconds(1)));
        assertDoesNotThrow(() -> policy.validateTtl(Duration.ofSeconds(60)));
    }

    @Test
    @DisplayName("申请边界: 0 / 负数 / null 被拒绝（BAD_REQUEST），不进入业务错误码")
    void nonPositiveTtlRejectedAsBadRequest() {
        IllegalArgumentException z = assertThrows(IllegalArgumentException.class,
                () -> policy.validateTtl(Duration.ZERO));
        IllegalArgumentException n = assertThrows(IllegalArgumentException.class,
                () -> policy.validateTtl(Duration.ofSeconds(-1)));
        IllegalArgumentException nu = assertThrows(IllegalArgumentException.class,
                () -> policy.validateTtl(null));
        log.info("[业务: 有效期] 非法 TTL 拒绝原因: {} | {} | {}", z.getMessage(), n.getMessage(), nu.getMessage());
    }

    @Test
    @DisplayName("申请边界: 超过上限 61 秒被拒绝，错误码 TTL_EXCEEDED")
    void ttlOverMaxRejectedWithCode() {
        SandboxException ex = assertThrows(SandboxException.class,
                () -> policy.validateTtl(Duration.ofSeconds(61)));
        assertEquals(SandboxErrorCode.TTL_EXCEEDED, ex.getCode());
        assertTrue(ex.getMessage().contains("maximum allowed ttl"));
    }

    @Test
    @DisplayName("配置边界: 最大上限必须为正数，否则启动即失败")
    void invalidConfigRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TtlPolicy(0));
    }

    @Test
    @DisplayName("续期边界: 未过期时在当前到期时间之上延长，贴边（恰好到绝对上限）允许")
    void renewWithinCapAcceptedAndFlushToCapAccepted() {
        Instant currentExpiry = fixedClock.instant().plusSeconds(30);
        Instant maxExpiry = fixedClock.instant().plusSeconds(60);
        // 再延 20 秒 -> 50 秒，允许
        Instant r1 = policy.renewedExpiresAt(currentExpiry, maxExpiry, Duration.ofSeconds(20), fixedClock);
        assertEquals(fixedClock.instant().plusSeconds(50), r1);
        // 再延到恰好 60 秒（贴边），允许
        Instant r2 = policy.renewedExpiresAt(currentExpiry, maxExpiry, Duration.ofSeconds(30), fixedClock);
        assertEquals(maxExpiry, r2);
    }

    @Test
    @DisplayName("续期边界: 越过绝对上限 1 秒即整笔拒绝（返回 null），不做静默截断")
    void renewBeyondCapRejected() {
        Instant currentExpiry = fixedClock.instant().plusSeconds(30);
        Instant maxExpiry = fixedClock.instant().plusSeconds(60);
        Instant r = policy.renewedExpiresAt(currentExpiry, maxExpiry, Duration.ofSeconds(31), fixedClock);
        assertNull(r, "越界续期必须整笔拒绝，到期时间保持不变");
    }

    @Test
    @DisplayName("续期边界: 续期时长 0/负数/null 被拒绝")
    void nonPositiveExtensionRejected() {
        assertThrows(IllegalArgumentException.class, () -> policy.validateExtension(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> policy.validateExtension(Duration.ofSeconds(-5)));
        assertThrows(IllegalArgumentException.class, () -> policy.validateExtension(null));
    }

    @Test
    @DisplayName("续期边界: 已过期的环境续期从当前时刻起算，仍受绝对上限约束")
    void renewAfterExpiryCountsFromNow() {
        Instant pastExpiry = fixedClock.instant().minusSeconds(100);
        Instant maxExpiry = fixedClock.instant().plusSeconds(60);
        // 从 now 起算延 60 秒恰好贴边
        assertEquals(maxExpiry, policy.renewedExpiresAt(pastExpiry, maxExpiry, Duration.ofSeconds(60), fixedClock));
        // 多 1 秒即越界
        assertNull(policy.renewedExpiresAt(pastExpiry, maxExpiry, Duration.ofSeconds(61), fixedClock));
    }

    @Test
    @DisplayName("绝对上限: maxExpiresAtFrom = 当前时刻 + 最大上限")
    void maxExpiresAtFromNow() {
        assertEquals(fixedClock.instant().plusSeconds(60), policy.maxExpiresAtFrom(fixedClock));
    }
}
