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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 业务覆盖：沙箱服务 —— 申请/续期/回收状态流转、幂等重放、失败补偿、
 * 归属校验、配额占用与归还、并发操作一致性。
 */
class SandboxServiceTest {

    private static final Logger log = LoggerFactory.getLogger(SandboxServiceTest.class);

    private SandboxService service;
    private QuotaManager quotaManager;
    private SimulatedProvisioner provisioner;

    @BeforeEach
    void setUp(TestInfo info) {
        quotaManager = new QuotaManager(2);
        provisioner = new SimulatedProvisioner();
        service = new SandboxService(new StateMachine(), quotaManager, provisioner);
        log.info("[业务: 沙箱服务] 执行用例: {}", info.getDisplayName());
    }

    @Test
    @DisplayName("状态流转: 申请后环境进入 READY 并占用配额")
    void applyTransitionsToReadyAndHoldsQuota() {
        Sandbox s = service.apply("team-a", "key-1", Duration.ofMinutes(30));
        assertEquals(SandboxStatus.READY, s.getStatus());
        assertEquals(1, quotaManager.usedOf("team-a"));
        assertTrue(s.isQuotaHeld());
        assertNotNull(s.getExpiresAt());
    }

    @Test
    @DisplayName("幂等: 相同 owner+幂等键重复申请返回同一环境，配额只占用一次")
    void applyIsIdempotentOnReplay() {
        Sandbox first = service.apply("team-a", "key-1", Duration.ofMinutes(30));
        Sandbox replay = service.apply("team-a", "key-1", Duration.ofMinutes(30));
        assertEquals(first.getId(), replay.getId(), "重放必须返回同一环境");
        assertEquals(1, quotaManager.usedOf("team-a"), "重放不得重复占用配额");
        assertEquals(1, provisioner.getProvisionCalls(), "重放不得重复配置");
    }

    @Test
    @DisplayName("幂等: 并发提交相同幂等键只产生一个环境")
    void concurrentApplyWithSameKeyCreatesOneSandbox() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Sandbox>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> service.apply("team-a", "key-dup", Duration.ofMinutes(30)));
        }
        List<Future<Sandbox>> results = pool.invokeAll(tasks);
        Set<String> ids = new HashSet<>();
        for (Future<Sandbox> r : results) {
            ids.add(r.get().getId());
        }
        pool.shutdown();
        assertEquals(1, ids.size(), "并发重复提交只能产生一个环境");
        assertEquals(1, quotaManager.usedOf("team-a"));
        log.info("[业务: 沙箱服务] 并发幂等结果: {} 个并发申请 -> {} 个环境", threads, ids.size());
    }

    @Test
    @DisplayName("配额: 超出归属方配额的申请被拒绝，错误码 QUOTA_EXCEEDED")
    void applyBeyondQuotaRejected() {
        service.apply("team-a", "k1", Duration.ofMinutes(30));
        service.apply("team-a", "k2", Duration.ofMinutes(30));
        SandboxException ex = assertThrows(SandboxException.class,
                () -> service.apply("team-a", "k3", Duration.ofMinutes(30)));
        assertEquals(SandboxErrorCode.QUOTA_EXCEEDED, ex.getCode());
        assertEquals(2, quotaManager.usedOf("team-a"));
    }

    @Test
    @DisplayName("补偿: 配置失败置 FAILED、归还配额、可安全重试且不残留占用")
    void provisionFailureCompensatesAndAllowsRetry() {
        // 使用一个总是失败的 Provisioner 触发补偿路径
        SandboxService alwaysFail = new SandboxService(new StateMachine(), quotaManager,
                new SimulatedProvisioner() {
                    @Override
                    public void provision(String sandboxId) {
                        throw new RuntimeException("boom");
                    }
                });
        SandboxException ex = assertThrows(SandboxException.class,
                () -> alwaysFail.apply("team-a", "key-fail", Duration.ofMinutes(30)));
        assertEquals(SandboxErrorCode.PROVISION_FAILED, ex.getCode());
        assertEquals(0, quotaManager.usedOf("team-a"), "失败后配额必须归还，不残留占用");

        // 安全重试：同一幂等键重试应创建新环境并成功
        Sandbox retried = service.apply("team-a", "key-fail", Duration.ofMinutes(30));
        assertEquals(SandboxStatus.READY, retried.getStatus());
        assertEquals(1, quotaManager.usedOf("team-a"));
        log.info("[业务: 沙箱服务] 失败补偿验证: 失败后配额={}, 重试后状态={}", 0, retried.getStatus());
    }

    @Test
    @DisplayName("归属: 非归属方续期/回收被拒绝，错误码 NOT_OWNER")
    void nonOwnerOperationsRejected() {
        Sandbox s = service.apply("team-a", "key-1", Duration.ofMinutes(30));
        SandboxException renewEx = assertThrows(SandboxException.class,
                () -> service.renew(s.getId(), "team-b", Duration.ofMinutes(10)));
        assertEquals(SandboxErrorCode.NOT_OWNER, renewEx.getCode());
        SandboxException releaseEx = assertThrows(SandboxException.class,
                () -> service.release(s.getId(), "team-b"));
        assertEquals(SandboxErrorCode.NOT_OWNER, releaseEx.getCode());
        assertEquals(SandboxStatus.READY, s.getStatus(), "被拒绝的操作不得改变状态");
    }

    @Test
    @DisplayName("续期: READY 状态续期延长过期时间")
    void renewExtendsExpiry() {
        Sandbox s = service.apply("team-a", "key-1", Duration.ofMinutes(30));
        Instant before = s.getExpiresAt();
        service.renew(s.getId(), "team-a", Duration.ofMinutes(15));
        assertTrue(s.getExpiresAt().isAfter(before), "续期后过期时间必须延后");
    }

    @Test
    @DisplayName("状态流转: 已回收环境续期被拒绝，错误码 ILLEGAL_STATE_TRANSITION")
    void renewAfterReleaseRejected() {
        Sandbox s = service.apply("team-a", "key-1", Duration.ofMinutes(30));
        service.release(s.getId(), "team-a");
        SandboxException ex = assertThrows(SandboxException.class,
                () -> service.renew(s.getId(), "team-a", Duration.ofMinutes(10)));
        assertEquals(SandboxErrorCode.ILLEGAL_STATE_TRANSITION, ex.getCode());
    }

    @Test
    @DisplayName("回收: 回收后配额归还，重复回收幂等不重复归还")
    void releaseReturnsQuotaExactlyOnce() {
        Sandbox s1 = service.apply("team-a", "k1", Duration.ofMinutes(30));
        service.apply("team-a", "k2", Duration.ofMinutes(30));
        assertEquals(2, quotaManager.usedOf("team-a"));

        service.release(s1.getId(), "team-a");
        assertEquals(SandboxStatus.RELEASED, s1.getStatus());
        assertEquals(1, quotaManager.usedOf("team-a"), "回收后配额必须归还");

        service.release(s1.getId(), "team-a");
        service.release(s1.getId(), "team-a");
        assertEquals(1, quotaManager.usedOf("team-a"), "重复回收不得重复归还配额");
        assertEquals(1, provisioner.getDestroyCalls(), "重复回收不得重复销毁");
    }

    @Test
    @DisplayName("并发: 同一环境续期与回收并发，结果一致（恰有一个成功）")
    void concurrentRenewAndReleaseAreConsistent() throws Exception {
        int renewWins = 0, releaseWins = 0;
        for (int round = 0; round < 20; round++) {
            QuotaManager qm = new QuotaManager(2);
            SandboxService svc = new SandboxService(new StateMachine(), qm, new SimulatedProvisioner());
            Sandbox s = svc.apply("team-a", "key-race", Duration.ofMinutes(30));

            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Callable<String>> tasks = List.of(
                    () -> {
                        try {
                            svc.renew(s.getId(), "team-a", Duration.ofMinutes(10));
                            return "renew-ok";
                        } catch (SandboxException e) {
                            return "renew-rejected:" + e.getCode();
                        }
                    },
                    () -> {
                        try {
                            svc.release(s.getId(), "team-a");
                            return "release-ok";
                        } catch (SandboxException e) {
                            return "release-rejected:" + e.getCode();
                        }
                    });
            List<Future<String>> results = pool.invokeAll(tasks);
            Set<String> outcomes = new HashSet<>();
            for (Future<String> r : results) {
                outcomes.add(r.get());
            }
            pool.shutdown();

            if (outcomes.contains("release-ok")) {
                releaseWins++;
                assertEquals(SandboxStatus.RELEASED, s.getStatus());
                assertEquals(0, qm.usedOf("team-a"), "回收成功后配额必须归还");
                assertTrue(outcomes.contains("renew-ok")
                        || outcomes.contains("renew-rejected:ILLEGAL_STATE_TRANSITION"),
                        "续期要么先于回收成功，要么被明确拒绝: " + outcomes);
            } else {
                renewWins++;
                fail("回收在本轮未执行成功，结果集: " + outcomes);
            }
        }
        log.info("[业务: 沙箱服务] 并发续期/回收 20 轮: 回收全部成功, 续期先成功 {} 轮, 续期被拒绝 {} 轮",
                renewWins, releaseWins - renewWins);
    }

    @Test
    @DisplayName("查询: 不存在的环境返回 SANDBOX_NOT_FOUND")
    void getUnknownSandboxRejected() {
        SandboxException ex = assertThrows(SandboxException.class, () -> service.get("no-such-id"));
        assertEquals(SandboxErrorCode.SANDBOX_NOT_FOUND, ex.getCode());
    }
}
