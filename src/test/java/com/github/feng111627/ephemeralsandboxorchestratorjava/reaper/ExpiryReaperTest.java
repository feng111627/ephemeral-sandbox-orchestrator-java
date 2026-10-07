package com.github.feng111627.ephemeralsandboxorchestratorjava.reaper;

import com.github.feng111627.ephemeralsandboxorchestratorjava.domain.Sandbox;
import com.github.feng111627.ephemeralsandboxorchestratorjava.domain.SandboxErrorCode;
import com.github.feng111627.ephemeralsandboxorchestratorjava.domain.SandboxStatus;
import com.github.feng111627.ephemeralsandboxorchestratorjava.exception.SandboxException;
import com.github.feng111627.ephemeralsandboxorchestratorjava.provision.SimulatedProvisioner;
import com.github.feng111627.ephemeralsandboxorchestratorjava.quota.QuotaManager;
import com.github.feng111627.ephemeralsandboxorchestratorjava.statemachine.StateMachine;
import com.github.feng111627.ephemeralsandboxorchestratorjava.service.SandboxService;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.DisplayName;

/**
 * 业务覆盖：到期自动回收 —— 到期扫描自动回收并归还配额、终态可查询、
 * 重复扫描幂等、与人工回收/续期并发时状态与配额结果一致（恰好归还一次）。
 * 全部使用秒级 TTL，单测内即可走完整条链路，无需长时间等待。
 */
class ExpiryReaperTest {

    private static final Logger log = LoggerFactory.getLogger(ExpiryReaperTest.class);

    private SandboxService service;
    private QuotaManager quotaManager;
    private SimulatedProvisioner provisioner;

    @BeforeEach
    void setUp(TestInfo info) {
        quotaManager = new QuotaManager(3);
        provisioner = new SimulatedProvisioner();
        service = new SandboxService(new StateMachine(), quotaManager, provisioner, Duration.ofSeconds(60));
        log.info("[业务: 到期自动回收] 执行用例: {}", info.getDisplayName());
    }

    @Test
    @DisplayName("自动回收: 到期后扫描把 READY 环境收为 RELEASED 并归还配额，终态可查询")
    void expiredSandboxAutoReleased() {
        Sandbox s = service.apply("team-a", "k-exp", Duration.ofSeconds(1));
        assertEquals(SandboxStatus.READY, s.getStatus());

        sleep(1200);
        List<String> reaped = service.reapExpiredSandboxes(Instant.now());

        assertEquals(List.of(s.getId()), reaped);
        Sandbox queried = service.get(s.getId());
        assertEquals(SandboxStatus.RELEASED, queried.getStatus(), "收尾后状态必须可查询且为 RELEASED");
        assertEquals(0, quotaManager.usedOf("team-a"), "自动回收必须归还配额");
        assertFalse(queried.isQuotaHeld(), "配额标记必须清除");
        assertEquals(1, provisioner.getDestroyCalls(), "底层环境必须被销毁一次");
        log.info("[业务: 到期自动回收] 环境 {} 已自动回收, 状态={}, 剩余配额={}/{}",
                s.getId(), queried.getStatus(),
                quotaManager.limitOf("team-a") - quotaManager.usedOf("team-a"), quotaManager.limitOf("team-a"));
    }

    @Test
    @DisplayName("自动回收: 未到期环境不被扫描回收")
    void unexpiredSandboxNotReaped() {
        Sandbox s = service.apply("team-a", "k-keep", Duration.ofSeconds(60));
        List<String> reaped = service.reapExpiredSandboxes(Instant.now());
        assertTrue(reaped.isEmpty());
        assertEquals(SandboxStatus.READY, s.getStatus());
        assertEquals(1, quotaManager.usedOf("team-a"));
    }

    @Test
    @DisplayName("幂等: 多轮扫描/自动回收重复执行是 no-op，不重复销毁、不重复归还")
    void repeatedReapingIsIdempotent() {
        Sandbox s = service.apply("team-a", "k-idem", Duration.ofSeconds(60));
        forceExpired(s);

        service.releaseAutomatically(s.getId());
        service.releaseAutomatically(s.getId());
        List<String> round1 = service.reapExpiredSandboxes(Instant.now());
        List<String> round2 = service.reapExpiredSandboxes(Instant.now());

        assertEquals(SandboxStatus.RELEASED, s.getStatus());
        assertEquals(0, quotaManager.usedOf("team-a"));
        assertEquals(1, provisioner.getDestroyCalls(), "重复自动回收不得重复销毁");
        assertTrue(round1.isEmpty() && round2.isEmpty(), "已回收环境不再被扫描命中");
    }

    @Test
    @DisplayName("并发一致: 到期后自动回收与人工回收同时发生，恰好一次销毁、配额恰好归还一次")
    void concurrentAutoAndManualReleaseConsistent() throws Exception {
        int coexistRounds = 0;
        for (int round = 0; round < 20; round++) {
            QuotaManager qm = new QuotaManager(3);
            SimulatedProvisioner prov = new SimulatedProvisioner();
            SandboxService svc = new SandboxService(new StateMachine(), qm, prov, Duration.ofSeconds(60));
            Sandbox s = svc.apply("team-a", "k-race-" + round, Duration.ofSeconds(60));
            forceExpired(s);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Callable<String>> tasks = List.of(
                    () -> {
                        jitter();
                        svc.releaseAutomatically(s.getId());
                        return "auto";
                    },
                    () -> {
                        try {
                            jitter();
                            svc.release(s.getId(), "team-a");
                            return "manual";
                        } catch (SandboxException e) {
                            return "err:" + e.getCode();
                        }
                    });
            List<Future<String>> results = pool.invokeAll(tasks);
            Set<String> who = new HashSet<>();
            for (Future<String> r2 : results) {
                who.add(r2.get());
            }
            pool.shutdown();

            assertEquals(SandboxStatus.RELEASED, s.getStatus(), "第 " + round + " 轮终态必须是 RELEASED: " + who);
            assertEquals(0, qm.usedOf("team-a"), "第 " + round + "轮配额必须恰好归还一次: " + who);
            assertEquals(1, prov.getDestroyCalls(), "第 " + round + " 轮底层销毁必须恰好一次: " + who);
            assertTrue(who.contains("auto") && who.contains("manual"),
                    "自动与人工回收并发调用都应安全返回（后者幂等）: " + who);
            coexistRounds++;
        }
        assertEquals(20, coexistRounds);
        log.info("[业务: 到期自动回收] 自动/人工回收并发 20 轮: 两方调用均安全共存, 每轮终态 RELEASED、销毁恰 1 次、配额恰还 1 次");
    }

    @Test
    @DisplayName("并发一致: 到期瞬间续期与自动回收竞争，续期先生效则不回收，否则续期被明确拒绝")
    void concurrentRenewAndAutoReleaseConsistent() throws Exception {
        int renewWins = 0, releasedRounds = 0;
        for (int round = 0; round < 30; round++) {
            QuotaManager qm = new QuotaManager(3);
            SandboxService svc = new SandboxService(new StateMachine(), qm,
                    new SimulatedProvisioner(), Duration.ofSeconds(60));
            Sandbox s = svc.apply("team-a", "k-renew-race-" + round, Duration.ofSeconds(60));
            // 到期边界设在约 4ms 后：任务抖动 0~10ms，续期有时早于到期、有时晚于到期，两侧都真实发生
            s.setExpiresAt(Instant.now().plusMillis(4));

            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Callable<String>> tasks = List.of(
                    () -> {
                        jitter(10);
                        try {
                            svc.renew(s.getId(), "team-a", Duration.ofSeconds(30));
                            return "renew-ok";
                        } catch (SandboxException e) {
                            return "renew-rejected:" + e.getCode();
                        }
                    },
                    () -> {
                        jitter(10);
                        List<String> r = svc.reapExpiredSandboxes(Instant.now());
                        return r.contains(s.getId()) ? "reaped" : "not-reaped";
                    });
            List<Future<String>> results = pool.invokeAll(tasks);
            Set<String> outcomes = new HashSet<>();
            for (Future<String> r2 : results) {
                outcomes.add(r2.get());
            }
            pool.shutdown();

            // 分类依据是最终状态与配额：RELEASED（无论由扫描器还是续期请求的惰性回收触发）
            if (s.getStatus() == SandboxStatus.RELEASED) {
                releasedRounds++;
                assertEquals(0, qm.usedOf("team-a"), "回收后配额必须归还: " + outcomes);
                assertFalse(outcomes.contains("renew-ok"), "已回收的环境上续期不可能成功: " + outcomes);
                assertTrue(outcomes.contains("renew-rejected:ILLEGAL_STATE_TRANSITION")
                                || outcomes.contains("renew-rejected:SANDBOX_EXPIRED"),
                        "回收先发生时续期必须被明确拒绝: " + outcomes);
            } else {
                renewWins++;
                assertEquals(SandboxStatus.READY, s.getStatus(), "续期先成功时环境必须仍 READY: " + outcomes);
                assertTrue(outcomes.contains("renew-ok"), "环境仍 READY 只可能是续期先成功: " + outcomes);
                assertTrue(s.getExpiresAt().isAfter(Instant.now()),
                        "续期先生效时新到期时间必须在未来: " + outcomes);
                assertEquals(1, qm.usedOf("team-a"), "续期成功时配额不得归还: " + outcomes);
            }
        }
        assertTrue(renewWins > 0 && releasedRounds > 0,
                "抖动后续期先成功与先回收两种结果都必须真实发生: renewWins=" + renewWins
                        + ", releasedRounds=" + releasedRounds);
        log.info("[业务: 到期自动回收] 续期/回收并发 30 轮: 续期先成功 {} 轮, 环境先被回收(扫描或惰性) {} 轮, 结果全部一致",
                renewWins, releasedRounds);
    }

    @Test
    @DisplayName("批量: 一轮扫描只收到期环境，不同归属方各自归还配额")
    void reaperReclaimsMultipleOwnersIndependently() {
        Sandbox a1 = service.apply("team-a", "ka1", Duration.ofSeconds(1));
        Sandbox a2 = service.apply("team-a", "ka2", Duration.ofSeconds(60));
        Sandbox b1 = service.apply("team-b", "kb1", Duration.ofSeconds(60));
        forceExpired(a1);
        forceExpired(b1);

        List<String> reaped = service.reapExpiredSandboxes(Instant.now());

        assertEquals(Set.of(a1.getId(), b1.getId()), new HashSet<>(reaped));
        assertEquals(SandboxStatus.RELEASED, a1.getStatus());
        assertEquals(SandboxStatus.RELEASED, b1.getStatus());
        assertEquals(SandboxStatus.READY, a2.getStatus());
        assertEquals(1, quotaManager.usedOf("team-a"), "team-a 到期环境已归还、未到期环境仍占用");
        assertEquals(0, quotaManager.usedOf("team-b"));
    }

    /** 并发用例随机微小抖动（0~5ms），让竞争双方都真实先执行过。 */
    private static void jitter() {
        jitter(5);
    }

    private static void jitter(long boundMs) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(0, boundMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 测试辅助：把到期时间拨到 1 秒前，立即模拟到期，避免真实等待。 */
    private static void forceExpired(Sandbox s) {
        s.setExpiresAt(Instant.now().minusSeconds(1));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
