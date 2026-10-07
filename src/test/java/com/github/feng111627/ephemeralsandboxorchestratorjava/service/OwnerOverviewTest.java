package com.github.feng111627.ephemeralsandboxorchestratorjava.service;

import com.github.feng111627.ephemeralsandboxorchestratorjava.domain.OwnerOverview;
import com.github.feng111627.ephemeralsandboxorchestratorjava.domain.Sandbox;
import com.github.feng111627.ephemeralsandboxorchestratorjava.expiry.MutableClock;
import com.github.feng111627.ephemeralsandboxorchestratorjava.provision.SimulatedProvisioner;
import com.github.feng111627.ephemeralsandboxorchestratorjava.quota.QuotaManager;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 业务覆盖：归属方总览 —— 使用中/已结束分类、配额占用与剩余、归属方隔离、
 * 人工/自动回收后在总览中可查、申请前额度判断。
 */
class OwnerOverviewTest {

    private static final Logger log = LoggerFactory.getLogger(OwnerOverviewTest.class);
    private static final Instant T0 = Instant.parse("2026-10-07T12:00:00Z");

    private MutableClock clock;
    private QuotaManager quotaManager;
    private SandboxService service;

    @BeforeEach
    void setUp(TestInfo info) {
        clock = MutableClock.startAt(T0);
        quotaManager = new QuotaManager(3);
        service = new SandboxService(new StateMachine(), quotaManager, new SimulatedProvisioner(),
                new TtlPolicy(600), clock);
        log.info("[业务: 归属方总览] 执行用例: {}", info.getDisplayName());
    }

    @Test
    @DisplayName("总览: 申请后进入 active，人工回收后转入 ended，配额数字同步变化")
    void overviewTracksActiveEndedAndQuota() {
        Sandbox s1 = service.apply("team-a", "k1", Duration.ofMinutes(10));
        clock.advance(Duration.ofMillis(1)); // 区分创建先后
        Sandbox s2 = service.apply("team-a", "k2", Duration.ofMinutes(10));

        OwnerOverview v = service.ownerOverview("team-a");
        assertEquals("team-a", v.owner());
        assertEquals(2, v.active().size());
        assertEquals(0, v.ended().size());
        assertEquals(2, v.usedQuota());
        assertEquals(3, v.quotaLimit());
        assertEquals(1, v.remainingQuota(), "应能看出还剩 1 个配额");
        assertEquals(List.of(s1.getId(), s2.getId()), v.active().stream().map(Sandbox::getId).toList(),
                "列表按创建时间排序");

        service.release(s1.getId(), "team-a");
        OwnerOverview v2 = service.ownerOverview("team-a");
        assertEquals(1, v2.active().size());
        assertEquals(1, v2.ended().size());
        assertEquals(s1.getId(), v2.ended().get(0).getId());
        assertEquals(1, v2.usedQuota());
        assertEquals(2, v2.remainingQuota(), "回收后剩余配额恢复");
    }

    @Test
    @DisplayName("总览: 到期自动回收的环境出现在 ended 中，且不再计入占用与剩余")
    void overviewReflectsAutoReap() {
        Sandbox s = service.apply("team-a", "k", Duration.ofSeconds(2));
        clock.advance(Duration.ofSeconds(3));
        service.reapExpiredSandboxes();

        OwnerOverview v = service.ownerOverview("team-a");
        assertEquals(0, v.active().size(), "自动回收后不再算作使用中");
        assertEquals(1, v.ended().size());
        assertEquals(s.getId(), v.ended().get(0).getId());
        assertEquals(0, v.usedQuota());
        assertEquals(3, v.remainingQuota(), "自动回收后额度全部可用");
        log.info("[业务: 归属方总览] 自动回收后 ended={} 剩余配额={}", v.ended().size(), v.remainingQuota());
    }

    @Test
    @DisplayName("总览: 配置失败（FAILED）计入 ended 且不占配额")
    void overviewIncludesFailedAsEnded() {
        SandboxService failing = new SandboxService(new StateMachine(), quotaManager,
                new SimulatedProvisioner() {
                    @Override
                    public void provision(String sandboxId) {
                        throw new RuntimeException("boom");
                    }
                }, new TtlPolicy(600), clock);
        assertThrows(RuntimeException.class, () -> failing.apply("team-a", "kf", Duration.ofMinutes(10)));

        OwnerOverview v = failing.ownerOverview("team-a");
        assertEquals(0, v.active().size());
        assertEquals(1, v.ended().size(), "FAILED 属于已结束");
        assertEquals(0, v.usedQuota(), "失败补偿后不占配额");
        assertEquals(3, v.remainingQuota());
    }

    @Test
    @DisplayName("总览: 不同归属方严格隔离；从无记录的归属方看到空总览与满额可用")
    void overviewIsolatedByOwner() {
        service.apply("team-a", "ka", Duration.ofMinutes(10));
        service.apply("team-b", "kb", Duration.ofMinutes(10));

        OwnerOverview va = service.ownerOverview("team-a");
        OwnerOverview vb = service.ownerOverview("team-b");
        assertEquals(1, va.active().size());
        assertEquals(1, vb.active().size());
        assertEquals("team-b", vb.active().get(0).getOwner());

        OwnerOverview empty = service.ownerOverview("team-c");
        assertEquals(0, empty.active().size());
        assertEquals(0, empty.ended().size());
        assertEquals(0, empty.usedQuota());
        assertEquals(3, empty.remainingQuota(), "新归属方满额可用");
    }

    @Test
    @DisplayName("总览: 独立配额上限在总览中可见")
    void overviewUsesCustomLimit() {
        quotaManager.setLimit("team-x", 1);
        service.apply("team-x", "kx", Duration.ofMinutes(10));
        OwnerOverview v = service.ownerOverview("team-x");
        assertEquals(1, v.quotaLimit());
        assertEquals(0, v.remainingQuota(), "占满后剩余为 0，申请前可据此判断不够用");
    }

    @Test
    @DisplayName("总览: owner 为空被拒绝")
    void blankOwnerRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.ownerOverview(" "));
    }
}
