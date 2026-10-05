package com.github.feng111627.ephemeralsandboxorchestratorjava.tests.sandbox;

import com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.domain.Sandbox;
import com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.domain.SandboxState;
import com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 业务：申请幂等与失败补偿
 */
@DisplayName("业务三：申请幂等、重复提交与失败后重试")
class IdempotencyTest extends SandboxTestBase {

    @Test
    @DisplayName("重复提交：同一 requestId 多次申请只产生一个沙箱，返回同一 id")
    void shouldDeduplicateRepeatedRequests() {
        Sandbox first = service.apply("idem-1", "team-a", "image=nginx", Duration.ofMinutes(10));
        Sandbox second = service.apply("idem-1", "team-a", "image=nginx", Duration.ofMinutes(10));
        Sandbox third = service.apply("idem-1", "team-a", "image=nginx", Duration.ofMinutes(10));

        assertEquals(first.getId(), second.getId());
        assertEquals(first.getId(), third.getId());
        assertEquals(1, quotaService.used("team-a"), "重复提交不得重复占用配额");
        assertEquals(1, repository.findAll().size(), "仓储中只能有一个沙箱");
        log.info("   ↳ 三次提交返回同一沙箱 {}，used={}", first.getId(), quotaService.used("team-a"));
    }

    @Test
    @DisplayName("参数冲突：同一 requestId 但 owner/spec/ttl 不一致被 IDEMPOTENCY_CONFLICT 拒绝")
    void shouldRejectConflictingFingerprint() {
        service.apply("idem-2", "team-a", "image=nginx", Duration.ofMinutes(10));

        expectSandboxException(ErrorCode.IDEMPOTENCY_CONFLICT,
                () -> service.apply("idem-2", "team-b", "image=nginx", Duration.ofMinutes(10)));
        expectSandboxException(ErrorCode.IDEMPOTENCY_CONFLICT,
                () -> service.apply("idem-2", "team-a", "image=redis", Duration.ofMinutes(10)));
        expectSandboxException(ErrorCode.IDEMPOTENCY_CONFLICT,
                () -> service.apply("idem-2", "team-a", "image=nginx", Duration.ofMinutes(11)));

        assertEquals(1, repository.findAll().size(), "冲突提交不得创建新沙箱");
        assertEquals(1, quotaService.used("team-a"));
    }

    @Test
    @DisplayName("失败后重放：fail 申请返回 FAILED 并归还配额，再次重放仍返回同一 FAILED 沙箱")
    void shouldReplayFailedSandboxWithoutNewReservation() {
        Sandbox failed = service.apply("idem-3", "team-a", "image=x;fail=true", Duration.ofMinutes(10));
        assertEquals(SandboxState.FAILED, failed.getState());
        assertEquals(0, quotaService.used("team-a"));

        Sandbox replayed = service.apply("idem-3", "team-a", "image=x;fail=true", Duration.ofMinutes(10));
        assertEquals(failed.getId(), replayed.getId(), "重放不能产生新环境，也不能丢已有环境");
        assertEquals(SandboxState.FAILED, replayed.getState());
        assertEquals(0, quotaService.used("team-a"), "重放不得重新占用配额");
        log.info("   ↳ 失败重放仍返回 {} state={} used={}",
                replayed.getId(), replayed.getState(), quotaService.used("team-a"));
    }

    @Test
    @DisplayName("失败后换单重试：新 requestId 可正常申请成功")
    void shouldAllowRetryWithNewRequestId() {
        Sandbox failed = service.apply("idem-4", "team-a", "image=x;fail=true", Duration.ofMinutes(10));
        assertEquals(SandboxState.FAILED, failed.getState());

        Sandbox ok = service.apply("idem-5", "team-a", "image=x", Duration.ofMinutes(10));
        assertEquals(SandboxState.READY, ok.getState());
        assertEquals(1, quotaService.used("team-a"), "失败申请的配额已释放，重试只需占用一个");
    }

    @Test
    @DisplayName("回收后重放：同一 requestId 重放返回已有的 RECYCLED 沙箱，不会复活")
    void shouldReplayRecycledSandbox() {
        Sandbox sb = service.apply("idem-6", "team-a", "image=x", Duration.ofMinutes(10));
        service.recycle(sb.getId(), "team-a");

        Sandbox replayed = service.apply("idem-6", "team-a", "image=x", Duration.ofMinutes(10));
        assertEquals(sb.getId(), replayed.getId());
        assertEquals(SandboxState.RECYCLED, replayed.getState());
        assertEquals(0, quotaService.used("team-a"), "重放已回收沙箱不得重新占配额");
    }

    @Test
    @DisplayName("参数校验：缺少 requestId/owner/spec、非法 TTL 被 VALIDATION_FAILED 拒绝")
    void shouldRejectInvalidInput() {
        expectSandboxException(ErrorCode.VALIDATION_FAILED,
                () -> service.apply(" ", "team-a", "image=x", Duration.ofMinutes(10)));
        expectSandboxException(ErrorCode.VALIDATION_FAILED,
                () -> service.apply("i", " ", "image=x", Duration.ofMinutes(10)));
        expectSandboxException(ErrorCode.VALIDATION_FAILED,
                () -> service.apply("i", "team-a", "  ", Duration.ofMinutes(10)));
        expectSandboxException(ErrorCode.VALIDATION_FAILED,
                () -> service.apply("i", "team-a", "image=x", Duration.ZERO));
        expectSandboxException(ErrorCode.VALIDATION_FAILED,
                () -> service.apply("i", "team-a", "image=x", Duration.ofSeconds(99999)));
        assertEquals(0, repository.findAll().size());
    }
}
