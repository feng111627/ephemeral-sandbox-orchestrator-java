package com.github.feng111627.ephemeralsandboxorchestratorjava.tests.sandbox;

import com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.domain.Sandbox;
import com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.domain.SandboxState;
import com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 业务：沙箱状态机流转
 */
@DisplayName("业务一：环境状态流转（PENDING/READY/FAILED/RECYCLED）")
class StateMachineTest extends SandboxTestBase {

    @Test
    @DisplayName("申请成功：PENDING 内部经过，对外得到 READY 并带上到期时间")
    void shouldApplyReachReady() {
        Instant start = Instant.now();
        Sandbox sb = service.apply("req-1", "team-a", "image=nginx", Duration.ofMinutes(10));

        assertEquals(SandboxState.READY, sb.getState());
        assertNotNull(sb.getExpiresAt());
        assertTrue(sb.getExpiresAt().isAfter(start.plus(Duration.ofMinutes(9))));
        assertTrue(sb.isHoldsQuota(), "READY 沙箱仍占用配额");
        log.info("   ↳ 申请成功 id={} state={} expiresAt={}", sb.getId(), sb.getState(), sb.getExpiresAt());
    }

    @Test
    @DisplayName("回收成功：READY -> RECYCLED，到期时间清空、配额释放标记置位")
    void shouldRecycleReady() {
        Sandbox sb = service.apply("req-2", "team-a", "image=nginx", Duration.ofMinutes(10));
        Sandbox recycled = service.recycle(sb.getId(), "team-a");

        assertEquals(SandboxState.RECYCLED, recycled.getState());
        assertNull(recycled.getExpiresAt());
        assertFalse(recycled.isHoldsQuota(), "回收后不再占用配额");
        log.info("   ↳ 回收完成 id={} holdsQuota={}", recycled.getId(), recycled.isHoldsQuota());
    }

    @Test
    @DisplayName("非法迁移：READY 被再次标记 READY 被拒绝")
    void shouldRejectDoubleMarkReady() {
        Sandbox sb = service.apply("req-3", "team-a", "image=nginx", Duration.ofMinutes(10));
        expectSandboxException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                () -> sb.markReady(Instant.now().plusSeconds(60)));
        assertEquals(SandboxState.READY, sb.getState());
    }

    @Test
    @DisplayName("非法迁移：非 READY 状态续期被拒绝并给出可区分原因")
    void shouldRejectRenewRecycled() {
        Sandbox sb = service.apply("req-4", "team-a", "image=nginx", Duration.ofMinutes(10));
        service.recycle(sb.getId(), "team-a");

        expectSandboxException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                () -> service.renew(sb.getId(), "team-a", Duration.ofMinutes(5)));
    }

    @Test
    @DisplayName("回收幂等：重复回收保持 RECYCLED，不报错")
    void shouldRecycleBeIdempotent() {
        Sandbox sb = service.apply("req-5", "team-a", "image=nginx", Duration.ofMinutes(10));
        Sandbox first = service.recycle(sb.getId(), "team-a");
        Sandbox second = service.recycle(sb.getId(), "team-a");

        assertEquals(SandboxState.RECYCLED, first.getState());
        assertEquals(SandboxState.RECYCLED, second.getState());
        assertFalse(second.isHoldsQuota());
        log.info("   ↳ 重复回收结论一致 state={}", second.getState());
    }

    @Test
    @DisplayName("续期：到期时间在原到期基础上延长，且不超过 now+上限")
    void shouldRenewExtendExpiryAndCap() {
        Sandbox sb = service.apply("req-6", "team-a", "image=nginx", Duration.ofMinutes(10));
        Instant before = sb.getExpiresAt();

        service.renew(sb.getId(), "team-a", Duration.ofMinutes(60));
        assertTrue(sb.getExpiresAt().isAfter(before), "续期后到期时间应后移");

        // 超大续期被钳制到上限 7200s 附近
        service.renew(sb.getId(), "team-a", Duration.ofDays(30));
        Instant ceiling = Instant.now().plusSeconds(7200);
        assertFalse(sb.getExpiresAt().isAfter(ceiling.plusSeconds(1)),
                "续期到期时间不得超过 now+maxTtl");
        log.info("   ↳ 续期后 expiresAt={}（已封顶）", sb.getExpiresAt());
    }
}
