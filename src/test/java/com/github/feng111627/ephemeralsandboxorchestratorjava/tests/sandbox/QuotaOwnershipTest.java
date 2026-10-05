package com.github.feng111627.ephemeralsandboxorchestratorjava.tests.sandbox;

import com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.domain.Sandbox;
import com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.domain.SandboxState;
import com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 业务：归属方配额与环境归属校验
 */
@DisplayName("业务二：配额占用/归还与归属方校验")
class QuotaOwnershipTest extends SandboxTestBase {

    @Test
    @DisplayName("配额边界：默认每团队 3 个，第 4 个申请被 QUOTA_EXCEEDED 拒绝")
    void shouldRejectWhenQuotaExceeded() {
        service.apply("q-1", "team-a", "image=x", Duration.ofMinutes(10));
        service.apply("q-2", "team-a", "image=x", Duration.ofMinutes(10));
        service.apply("q-3", "team-a", "image=x", Duration.ofMinutes(10));
        assertEquals(3, quotaService.used("team-a"));

        expectSandboxException(ErrorCode.QUOTA_EXCEEDED,
                () -> service.apply("q-4", "team-a", "image=x", Duration.ofMinutes(10)));
        assertEquals(3, quotaService.used("team-a"), "拒绝后不能多占配额");
    }

    @Test
    @DisplayName("配额归还：回收一个后配额立即可用，可再申请")
    void shouldReleaseQuotaAfterRecycle() {
        Sandbox first = service.apply("q-10", "team-a", "image=x", Duration.ofMinutes(10));
        service.apply("q-11", "team-a", "image=x", Duration.ofMinutes(10));
        service.apply("q-12", "team-a", "image=x", Duration.ofMinutes(10));
        assertEquals(3, quotaService.used("team-a"));

        service.recycle(first.getId(), "team-a");
        assertEquals(2, quotaService.used("team-a"), "回收后配额归还");

        Sandbox again = service.apply("q-13", "team-a", "image=x", Duration.ofMinutes(10));
        assertEquals(SandboxState.READY, again.getState());
        assertEquals(3, quotaService.used("team-a"));
        log.info("   ↳ 回收后重新申请成功，used={}", quotaService.used("team-a"));
    }

    @Test
    @DisplayName("不重复归还：对同一沙箱重复回收，配额只归还一次")
    void shouldNotReleaseQuotaTwice() {
        Sandbox sb = service.apply("q-20", "team-a", "image=x", Duration.ofMinutes(10));
        service.apply("q-21", "team-a", "image=x", Duration.ofMinutes(10));
        service.recycle(sb.getId(), "team-a");
        int afterFirst = quotaService.used("team-a");
        service.recycle(sb.getId(), "team-a");
        service.recycle(sb.getId(), "team-a");

        assertEquals(afterFirst, quotaService.used("team-a"), "重复回收不得重复归还配额");
        assertFalse(quotaService.release("never-existed"), "未知归属方归还应被防御为无变化");
        log.info("   ↳ 三次回收后 used={}（只归还一次）", quotaService.used("team-a"));
    }

    @Test
    @DisplayName("失败补偿：配置失败后标记 FAILED 且配额已归还，不残留占用")
    void shouldCompensateQuotaWhenProvisionFails() {
        Sandbox sb = service.apply("q-30", "team-a", "image=x;fail=true", Duration.ofMinutes(10));

        assertEquals(SandboxState.FAILED, sb.getState());
        assertFalse(sb.isHoldsQuota(), "失败后不能继续占用配额");
        assertEquals(0, quotaService.used("team-a"));
        assertNotNull(sb.getFailReason());
        log.info("   ↳ 配置失败已补偿 state={} reason={} used={}",
                sb.getState(), sb.getFailReason(), quotaService.used("team-a"));
    }

    @Test
    @DisplayName("租户隔离：不同归属方配额独立计算，互不影响")
    void shouldIsolateQuotaByOwner() {
        for (int i = 0; i < 3; i++) {
            service.apply("t-a-" + i, "team-a", "image=x", Duration.ofMinutes(10));
        }
        for (int i = 0; i < 2; i++) {
            service.apply("t-b-" + i, "team-b", "image=x", Duration.ofMinutes(10));
        }
        assertEquals(3, quotaService.used("team-a"));
        assertEquals(2, quotaService.used("team-b"));

        expectSandboxException(ErrorCode.QUOTA_EXCEEDED,
                () -> service.apply("t-a-x", "team-a", "image=x", Duration.ofMinutes(10)));
        Sandbox bThird = service.apply("t-b-2", "team-b", "image=x", Duration.ofMinutes(10));
        assertEquals(SandboxState.READY, bThird.getState());
    }

    @Test
    @DisplayName("归属校验：非归属方续期/回收均被 NOT_OWNER 拒绝，状态不变")
    void shouldRejectNonOwnerOperations() {
        Sandbox sb = service.apply("q-40", "team-a", "image=x", Duration.ofMinutes(10));

        expectSandboxException(ErrorCode.NOT_OWNER,
                () -> service.renew(sb.getId(), "team-b", Duration.ofMinutes(5)));
        expectSandboxException(ErrorCode.NOT_OWNER,
                () -> service.recycle(sb.getId(), "team-b"));

        assertEquals(SandboxState.READY, sb.getState(), "被拒绝后状态保持 READY");
        assertEquals(1, quotaService.used("team-a"), "被拒绝后配额不释放");
    }

    @Test
    @DisplayName("不存在的沙箱：续期/回收/查询返回 SANDBOX_NOT_FOUND")
    void shouldReportNotFound() {
        expectSandboxException(ErrorCode.SANDBOX_NOT_FOUND,
                () -> service.get("sb-missing"));
        expectSandboxException(ErrorCode.SANDBOX_NOT_FOUND,
                () -> service.recycle("sb-missing", "team-a"));
    }
}
