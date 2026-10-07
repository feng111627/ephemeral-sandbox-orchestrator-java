package com.github.feng111627.ephemeralsandboxorchestratorjava.expiry;

import com.github.feng111627.ephemeralsandboxorchestratorjava.domain.Sandbox;
import com.github.feng111627.ephemeralsandboxorchestratorjava.domain.SandboxErrorCode;
import com.github.feng111627.ephemeralsandboxorchestratorjava.domain.SandboxStatus;
import com.github.feng111627.ephemeralsandboxorchestratorjava.exception.SandboxException;
import com.github.feng111627.ephemeralsandboxorchestratorjava.provision.SimulatedProvisioner;
import com.github.feng111627.ephemeralsandboxorchestratorjava.quota.QuotaManager;
import com.github.feng111627.ephemeralsandboxorchestratorjava.service.SandboxService;
import com.github.feng111627.ephemeralsandboxorchestratorjava.statemachine.StateMachine;
import com.github.feng111627.ephemeralsandboxorchestratorjava.ttl.TtlPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 业务覆盖：到期自动回收 —— 到期后平台自动收尾并归还配额、未到期不误收、
 * 重复扫描幂等、自动回收与人工回收/续期并发结果一致（配额恰好一次、状态可预期）、
 * 申请 TTL 与续期上限在服务层的拒绝语义。
 */
class AutoReapServiceTest {

    private static final Logger log = LoggerFactory.getLogger(AutoReapServiceTest.class);

    private static final Instant T0 = Instant.parse("2026-10-07T10:00:00Z");

    private MutableClock clock;
    private QuotaManager quotaManager;
    private SimulatedProvisioner provisioner;
    private SandboxService service;

    @BeforeEach
    void setUp(TestInfo info) {
        clock = MutableClock.startAt(T0);
        quotaManager = new QuotaManager(3);
        provisioner = new SimulatedProvisioner();
        TtlPolicy ttlPolicy = new TtlPolicy(60);
        service = new SandboxService(new StateMachine(), quotaManager, provisioner, ttlPolicy, clock);
        log.info("[业务: 到期自动回收] 执行用例: {}", info.getDisplayName());
    }

    @Test
    @DisplayName("自动回收: 到期后扫描自动置 RELEASED 并归还配额，未到期不受影响")
    void expiredSandboxAutoReleasedAndQuotaReturned() {
        Sandbox shortLived = service.apply("team-a", "k-short", Duration.ofSeconds(2));
        Sandbox longLived = service.apply("team-a", "k-long", Duration.ofSeconds(30));
        assertEquals(2, quotaManager.usedOf("team-a"));

        // 前进 1 秒：都未到期，扫描无动作
        clock.advance(Duration.ofSeconds(1));
        assertEquals(0, service.reapExpiredSandboxes());
        assertEquals(SandboxStatus.READY, shortLived.getStatus());

        // 前进到 2 秒：短 TTL 到期被自动回收
        clock.advance(Duration.ofSeconds(1));
        int reaped = service.reapExpiredSandboxes();
        assertEquals(1, reaped);
        assertEquals(SandboxStatus.RELEASED, shortLived.getStatus(), "到期后状态必须可查询且为 RELEASED");
        assertFalse(shortLived.isQuotaHeld(), "自动回收必须归还配额");
        assertEquals(1, quotaManager.usedOf("team-a"), "配额必须只归还到期环境的那一份");
        assertEquals(SandboxStatus.READY, longLived.getStatus(), "未到期环境不能被误收");
        log.info("[业务: 到期自动回收] t=2s 自动回收 1 个, 剩余占用={}", quotaManager.usedOf("team-a"));
    }

    @Test
    @DisplayName("自动回收: 到期边界（恰好到期）即被回收，提前 1 毫秒不收")
    void expiryBoundaryIsExact() {
        Sandbox s = service.apply("team-a", "k", Duration.ofSeconds(2));
        clock.advance(Duration.ofMillis(1999));
        assertEquals(0, service.reapExpiredSandboxes(), "到期前 1ms 不能回收");
        assertEquals(SandboxStatus.READY, s.getStatus());
        clock.advance(Duration.ofMillis(1));
        assertEquals(1, service.reapExpiredSandboxes(), "恰好到期必须回收");
        assertEquals(SandboxStatus.RELEASED, s.getStatus());
    }

    @Test
    @DisplayName("幂等: 到期后重复扫描 + 人工再回收，配额只归还一次、销毁只执行一次")
    void repeatedReapAndManualReleaseIsIdempotent() {
        Sandbox s = service.apply("team-a", "k", Duration.ofSeconds(2));
        clock.advance(Duration.ofSeconds(3));
        assertEquals(1, service.reapExpiredSandboxes());
        assertEquals(0, service.reapExpiredSandboxes(), "重复扫描不应再回收");
        Sandbox again = service.release(s.getId(), "team-a");
        assertEquals(SandboxStatus.RELEASED, again.getStatus());
        assertEquals(0, quotaManager.usedOf("team-a"), "自动+人工都只能归还一次配额");
        assertEquals(1, provisioner.getDestroyCalls(), "destroy 只能执行一次");
    }

    @Test
    @DisplayName("并发一致: 自动回收与人工回收同时进行，配额恰好归还一次、终态 RELEASED")
    void concurrentAutoAndManualReleaseConsistent() throws Exception {
        for (int round = 0; round < 20; round++) {
            MutableClock c = MutableClock.startAt(T0);
            QuotaManager qm = new QuotaManager(3);
            SimulatedProvisioner p = new SimulatedProvisioner();
            SandboxService svc = new SandboxService(new StateMachine(), qm, p, new TtlPolicy(60), c);
            Sandbox s = svc.apply("team-a", "k-race-" + round, Duration.ofSeconds(2));
            c.advance(Duration.ofSeconds(3)); // 已到期

            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Callable<Integer>> tasks = List.of(
                    () -> svc.reapExpiredSandboxes(),
                    () -> {
                        svc.release(s.getId(), "team-a");
                        return 0;
                    });
            List<Future<Integer>> results = pool.invokeAll(tasks);
            int reaped = 0;
            for (Future<Integer> r : results) {
                reaped += r.get();
            }
            pool.shutdown();

            assertEquals(SandboxStatus.RELEASED, s.getStatus(), "第 " + round + " 轮终态必须为 RELEASED");
            assertEquals(0, qm.usedOf("team-a"), "第 " + round + " 轮配额必须恰好归还一次");
            assertEquals(1, p.getDestroyCalls(), "第 " + round + " 轮 destroy 必须恰好一次");
            assertTrue(reaped <= 1, "第 " + round + " 轮自动回收计数不应重复: " + reaped);
        }
        log.info("[业务: 到期自动回收] 自动/人工并发回收 20 轮: 终态一致、配额与 destroy 均恰好一次");
    }

    @Test
    @DisplayName("并发一致: 到期瞬间自动回收与续期竞争，续期要么先生效延后到期，要么被明确拒绝")
    void concurrentReapAndRenewConsistent() throws Exception {
        int renewedFirst = 0;
        int rejectedByState = 0;
        int autoWonButRenewBlocked = 0;
        for (int round = 0; round < 30; round++) {
            MutableClock c = MutableClock.startAt(T0);
            QuotaManager qm = new QuotaManager(3);
            SandboxService svc = new SandboxService(new StateMachine(), qm, new SimulatedProvisioner(),
                    new TtlPolicy(60), c);
            Sandbox s = svc.apply("team-a", "k-rr-" + round, Duration.ofSeconds(2));
            c.advance(Duration.ofSeconds(3)); // 到期时刻：reaper 与 renew 竞争

            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Callable<String>> tasks = List.of(
                    () -> {
                        int n = svc.reapExpiredSandboxes();
                        return "reap:" + n;
                    },
                    () -> {
                        try {
                            // 到期后续期：从 now 起算延 10 秒，在绝对上限 60 秒内
                            svc.renew(s.getId(), "team-a", Duration.ofSeconds(10));
                            return "renew-ok";
                        } catch (SandboxException e) {
                            return "renew-reject:" + e.getCode();
                        }
                    });
            List<Future<String>> results = pool.invokeAll(tasks);
            Set<String> outcomes = new HashSet<>();
            for (Future<String> r : results) {
                outcomes.add(r.get());
            }
            pool.shutdown();

            // 无论谁先，结果只能是两种一致状态之一
            if (s.getStatus() == SandboxStatus.READY && outcomes.contains("renew-ok")) {
                renewedFirst++;
                assertTrue(outcomes.contains("reap:0"),
                        "续期先成功且把到期时间延到未来后，本轮扫描不得再回收: " + outcomes);
                assertTrue(s.getExpiresAt().isAfter(c.instant()), "续期后的到期时间必须在未来");
            } else {
                autoWonButRenewBlocked++;
                assertEquals(SandboxStatus.RELEASED, s.getStatus(), "自动回收先赢则终态 RELEASED: " + outcomes);
                assertTrue(outcomes.contains("renew-reject:ILLEGAL_STATE_TRANSITION"),
                        "回收后续期必须被明确拒绝: " + outcomes);
                assertEquals(0, qm.usedOf("team-a"), "自动回收后配额必须已归还: " + outcomes);
            }
            if (outcomes.contains("renew-reject:ILLEGAL_STATE_TRANSITION")) {
                rejectedByState++;
            }
        }
        log.info("[业务: 到期自动回收] 自动回收/续期并发 30 轮: 续期先成功 {} 轮, 自动先回收且续期被拒 {} 轮",
                renewedFirst, autoWonButRenewBlocked);
        assertTrue(renewedFirst + rejectedByState == 30,
                "每轮续期都必须有明确结果：先生效或被明确拒绝");
    }

    @Test
    @DisplayName("申请校验: 非法 TTL（0/负/超上限）被拒，不占配额、不留下环境")
    void applyWithIllegalTtlRejectedCleanly() {
        assertThrows(IllegalArgumentException.class,
                () -> service.apply("team-a", "k0", Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> service.apply("team-a", "kn", Duration.ofSeconds(-1)));
        SandboxException over = assertThrows(SandboxException.class,
                () -> service.apply("team-a", "kbig", Duration.ofSeconds(61)));
        assertEquals(SandboxErrorCode.TTL_EXCEEDED, over.getCode());
        assertEquals(0, quotaManager.usedOf("team-a"), "非法 TTL 不得占用任何配额");
        // 被拒的幂等键可以用合法 TTL 重新申请，证明没有半成品/残留索引
        Sandbox s = service.apply("team-a", "kbig", Duration.ofSeconds(60));
        assertEquals(SandboxStatus.READY, s.getStatus());
        assertEquals(1, quotaManager.usedOf("team-a"));
    }

    @Test
    @DisplayName("续期校验: 越过绝对上限整笔拒绝且到期时间不变；贴边延长允许")
    void renewBeyondAbsoluteCapRejected() {
        // TTL 2 秒，绝对上限 T0+60s
        Sandbox s = service.apply("team-a", "k", Duration.ofSeconds(2));
        Instant before = s.getExpiresAt();
        // 延 60 秒会到 T0+62s，越过 T0+60s 上限 -> 拒绝
        SandboxException ex = assertThrows(SandboxException.class,
                () -> service.renew(s.getId(), "team-a", Duration.ofSeconds(60)));
        assertEquals(SandboxErrorCode.TTL_EXCEEDED, ex.getCode());
        assertEquals(before, s.getExpiresAt(), "被拒绝的续期不得改变到期时间");

        // 贴边：从当前 expiry(T0+2) 延 58 秒恰好到 T0+60，允许
        service.renew(s.getId(), "team-a", Duration.ofSeconds(58));
        assertEquals(s.getMaxExpiresAt(), s.getExpiresAt(), "贴边续期后到期时间等于绝对上限");

        // 已到顶后再续 1 秒必然越界
        SandboxException ex2 = assertThrows(SandboxException.class,
                () -> service.renew(s.getId(), "team-a", Duration.ofSeconds(1)));
        assertEquals(SandboxErrorCode.TTL_EXCEEDED, ex2.getCode());
        log.info("[业务: 到期自动回收] 续期上限生效: 绝对上限={} 到期={}", s.getMaxExpiresAt(), s.getExpiresAt());
    }
}
