package com.github.feng111627.ephemeralsandboxorchestratorjava.service;

import com.github.feng111627.ephemeralsandboxorchestratorjava.domain.Sandbox;
import com.github.feng111627.ephemeralsandboxorchestratorjava.domain.SandboxErrorCode;
import com.github.feng111627.ephemeralsandboxorchestratorjava.domain.SandboxStatus;
import com.github.feng111627.ephemeralsandboxorchestratorjava.exception.SandboxException;
import com.github.feng111627.ephemeralsandboxorchestratorjava.provision.SimulatedProvisioner;
import com.github.feng111627.ephemeralsandboxorchestratorjava.quota.QuotaManager;
import com.github.feng111627.ephemeralsandboxorchestratorjava.statemachine.StateMachine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 业务覆盖：有效期策略 —— 申请 TTL 必须是合法正数且不超过可配置上限；
 * 非法/超限申请不占配额、不产生半成品；续期后到期时间不得超过 now + 上限；
 * 过期环境续期被明确拒绝。
 */
class TtlPolicyTest {

    private static final Logger log = LoggerFactory.getLogger(TtlPolicyTest.class);

    /** 用很短的上限，单测即可覆盖边界，无需长时间等待。 */
    private static final Duration MAX_TTL = Duration.ofSeconds(10);

    private SandboxService service;
    private QuotaManager quotaManager;

    @BeforeEach
    void setUp(TestInfo info) {
        quotaManager = new QuotaManager(3);
        service = new SandboxService(new StateMachine(), quotaManager, new SimulatedProvisioner(), MAX_TTL);
        log.info("[业务: 有效期策略] 执行用例: {}", info.getDisplayName());
    }

    @Test
    @DisplayName("申请边界: TTL 恰好等于最大上限允许通过")
    void ttlEqualToMaxAllowed() {
        Sandbox s = service.apply("team-a", "k-eq", MAX_TTL);
        assertEquals(SandboxStatus.READY, s.getStatus());
        assertEquals(1, quotaManager.usedOf("team-a"));
    }

    @Test
    @DisplayName("申请拒绝: TTL 为 0 或负数，错误码 INVALID_TTL，不占配额不留半成品")
    void nonPositiveTtlRejected() {
        for (Duration bad : new Duration[]{Duration.ZERO, Duration.ofSeconds(-1), Duration.ofMillis(-500)}) {
            SandboxException ex = assertThrows(SandboxException.class,
                    () -> service.apply("team-a", "k-bad-" + bad, bad));
            assertEquals(SandboxErrorCode.INVALID_TTL, ex.getCode());
        }
        assertEquals(0, quotaManager.usedOf("team-a"), "非法 TTL 不得占用任何配额");
        OwnerOverview overview = service.getOverview("team-a");
        assertTrue(overview.active().isEmpty() && overview.ended().isEmpty(),
                "非法 TTL 不得残留任何环境（含半成品）");
        log.info("[业务: 有效期策略] 非法 TTL 申请全部被拒, 占用配额={}",
                quotaManager.usedOf("team-a"));
    }

    @Test
    @DisplayName("申请拒绝: TTL 超过最大上限，错误码 TTL_LIMIT_EXCEEDED，不占配额不留半成品")
    void ttlBeyondMaxRejected() {
        SandboxException ex = assertThrows(SandboxException.class,
                () -> service.apply("team-a", "k-long", MAX_TTL.plusSeconds(1)));
        assertEquals(SandboxErrorCode.TTL_LIMIT_EXCEEDED, ex.getCode());
        assertEquals(0, quotaManager.usedOf("team-a"), "超限申请不得占用配额");
        assertTrue(service.getOverview("team-a").active().isEmpty(), "超限申请不得产生半成品环境");
    }

    @Test
    @DisplayName("续期边界: 续期后到期时间恰好在 now+上限 处允许通过")
    void renewExactlyAtBoundaryAllowed() {
        Sandbox s = service.apply("team-a", "k-renew", Duration.ofSeconds(5));
        assertDoesNotThrow(() -> service.renew(s.getId(), "team-a", Duration.ofSeconds(5)),
                "从申请时刻起续到上限边界应被允许");
    }

    @Test
    @DisplayName("续期拒绝: 续期会把到期时间推过 now+上限，错误码 TTL_LIMIT_EXCEEDED 且到期时间不变")
    void renewBeyondMaxRejectedAtomically() {
        Sandbox s = service.apply("team-a", "k-renew-long", Duration.ofSeconds(5));
        var before = s.getExpiresAt();
        SandboxException ex = assertThrows(SandboxException.class,
                () -> service.renew(s.getId(), "team-a", Duration.ofSeconds(6)));
        assertEquals(SandboxErrorCode.TTL_LIMIT_EXCEEDED, ex.getCode());
        assertEquals(before, s.getExpiresAt(), "超限续期必须整体拒绝，不得部分生效");
        assertEquals(SandboxStatus.READY, s.getStatus());
        assertEquals(1, quotaManager.usedOf("team-a"));
    }

    @Test
    @DisplayName("续期拒绝: 非正数续期时长，错误码 INVALID_TTL")
    void renewWithNonPositiveExtensionRejected() {
        Sandbox s = service.apply("team-a", "k-renew-zero", Duration.ofSeconds(5));
        assertEquals(SandboxErrorCode.INVALID_TTL,
                assertThrows(SandboxException.class,
                        () -> service.renew(s.getId(), "team-a", Duration.ZERO)).getCode());
        assertEquals(SandboxErrorCode.INVALID_TTL,
                assertThrows(SandboxException.class,
                        () -> service.renew(s.getId(), "team-a", Duration.ofSeconds(-3))).getCode());
    }

    @Test
    @DisplayName("续期拒绝: 已过期环境续期返回 SANDBOX_EXPIRED，且被顺手惰性自动回收（终态 RELEASED、配额归还）")
    void renewExpiredSandboxRejected() throws Exception {
        Sandbox s = service.apply("team-a", "k-expired", Duration.ofSeconds(1));
        Thread.sleep(1100);
        // 到期但调度扫描尚未发生：续期请求应惰性触发回收，再明确拒绝
        SandboxException ex = assertThrows(SandboxException.class,
                () -> service.renew(s.getId(), "team-a", Duration.ofSeconds(5)));
        assertEquals(SandboxErrorCode.SANDBOX_EXPIRED, ex.getCode());
        assertEquals(SandboxStatus.RELEASED, s.getStatus(), "过期续期必须顺手把环境终态化");
        assertEquals(0, quotaManager.usedOf("team-a"), "惰性回收必须归还配额");
        log.info("[业务: 有效期策略] 过期续期被拒绝 code={} 且环境已自动回收", ex.getCode());
    }
}
