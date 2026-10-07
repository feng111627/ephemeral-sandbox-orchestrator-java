package com.github.feng111627.ephemeralsandboxorchestratorjava.service;

import com.github.feng111627.ephemeralsandboxorchestratorjava.domain.Sandbox;
import com.github.feng111627.ephemeralsandboxorchestratorjava.domain.SandboxStatus;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 业务覆盖：名下总览 —— 按归属方查询在用/已结束环境、配额上限/已用/剩余，
 * 回收（含自动回收）与失败补偿后总览同步变化；归属方之间相互隔离。
 */
class OwnerOverviewTest {

    private static final Logger log = LoggerFactory.getLogger(OwnerOverviewTest.class);

    private SandboxService service;
    private QuotaManager quotaManager;

    @BeforeEach
    void setUp(TestInfo info) {
        quotaManager = new QuotaManager(3);
        service = new SandboxService(new StateMachine(), quotaManager, new SimulatedProvisioner(), Duration.ofHours(1));
        log.info("[业务: 名下总览] 执行用例: {}", info.getDisplayName());
    }

    @Test
    @DisplayName("总览: 申请后出现在用列表，剩余配额 = 上限 - 已用")
    void activeSandboxesAndRemainingQuota() {
        service.apply("team-a", "k1", Duration.ofMinutes(10));
        service.apply("team-a", "k2", Duration.ofMinutes(10));

        OwnerOverview o = service.getOverview("team-a");
        assertEquals(2, o.active().size());
        assertTrue(o.ended().isEmpty());
        assertEquals(3, o.quotaLimit());
        assertEquals(2, o.quotaUsed());
        assertEquals(1, o.quotaRemaining(), "申请前可据此判断还够不够用");
    }

    @Test
    @DisplayName("总览: 人工回收后环境从在用移到已结束，剩余配额恢复")
    void manualReleaseMovesSandboxToEnded() {
        Sandbox s1 = service.apply("team-a", "k1", Duration.ofMinutes(10));
        service.apply("team-a", "k2", Duration.ofMinutes(10));
        service.release(s1.getId(), "team-a");

        OwnerOverview o = service.getOverview("team-a");
        assertEquals(1, o.active().size());
        assertEquals(1, o.ended().size());
        assertEquals(SandboxStatus.RELEASED, o.ended().get(0).getStatus());
        assertEquals(1, o.quotaUsed());
        assertEquals(2, o.quotaRemaining());
    }

    @Test
    @DisplayName("总览: 到期自动回收后同样进入已结束列表并归还配额")
    void autoReleaseReflectsInOverview() {
        Sandbox s = service.apply("team-a", "k-exp", Duration.ofMinutes(10));
        service.expireAtForTesting(s.getId(), Instant.now().minusSeconds(1));

        List<String> reaped = service.reapExpiredSandboxes(Instant.now());
        assertEquals(List.of(s.getId()), reaped);

        OwnerOverview o = service.getOverview("team-a");
        assertTrue(o.active().isEmpty());
        assertEquals(1, o.ended().size());
        assertEquals(0, o.quotaUsed());
        assertEquals(3, o.quotaRemaining());
        log.info("[业务: 名下总览] 自动回收后: 在用={}, 已结束={}, 剩余配额={}",
                o.active().size(), o.ended().size(), o.quotaRemaining());
    }

    @Test
    @DisplayName("总览: 配置失败补偿的环境计入已结束（FAILED）且不占配额")
    void failedSandboxAppearsInEndedWithoutQuota() {
        SandboxService failService = new SandboxService(new StateMachine(), quotaManager,
                new SimulatedProvisioner() {
                    @Override
                    public void provision(String sandboxId) {
                        throw new RuntimeException("boom");
                    }
                }, Duration.ofHours(1));
        assertThrows(Exception.class, () -> failService.apply("team-a", "k-fail", Duration.ofMinutes(10)));

        OwnerOverview o = service.getOverview("team-a");
        // 两个 service 共享同一 QuotaManager，但存储各自独立；此处以配额维度验证不残留
        assertEquals(0, o.quotaUsed(), "失败补偿不得占用配额");
        assertEquals(3, o.quotaRemaining());

        OwnerOverview failView = failService.getOverview("team-a");
        assertEquals(1, failView.ended().size());
        assertEquals(SandboxStatus.FAILED, failView.ended().get(0).getStatus());
        assertTrue(failView.active().isEmpty());
    }

    @Test
    @DisplayName("总览: 不同归属方视图隔离，互不计入")
    void ownersAreIsolatedInOverview() {
        service.apply("team-a", "ka", Duration.ofMinutes(10));
        service.apply("team-b", "kb", Duration.ofMinutes(10));

        OwnerOverview a = service.getOverview("team-a");
        OwnerOverview b = service.getOverview("team-b");
        OwnerOverview c = service.getOverview("team-c");

        assertEquals(1, a.active().size());
        assertEquals("team-a", a.active().get(0).getOwner());
        assertEquals(1, b.active().size());
        assertTrue(c.active().isEmpty() && c.ended().isEmpty());
        assertEquals(3, c.quotaLimit());
        assertEquals(0, c.quotaUsed());
        assertEquals(3, c.quotaRemaining());
    }
}
