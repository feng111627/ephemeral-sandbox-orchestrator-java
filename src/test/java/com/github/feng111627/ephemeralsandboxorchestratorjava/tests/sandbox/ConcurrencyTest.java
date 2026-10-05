package com.github.feng111627.ephemeralsandboxorchestratorjava.tests.sandbox;

import com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.domain.Sandbox;
import com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.domain.SandboxState;
import com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.exception.ErrorCode;
import com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.exception.SandboxException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 业务：并发一致性与过期回收
 */
@DisplayName("业务四：并发操作一致性、配额抢占与过期回收")
class ConcurrencyTest extends SandboxTestBase {

    @Test
    @DisplayName("并发重复申请：同一 requestId 的 20 个并发请求只产生一个沙箱、占用一个配额")
    void shouldDeduplicateConcurrentApplies() throws Exception {
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                // delayMillis 放大配置窗口，迫使并发请求真正交错
                return service.apply("race-1", "team-a", "image=x;delayMillis=80",
                        Duration.ofMinutes(10)).getId();
            }));
        }
        start.countDown();

        for (Future<String> f : futures) {
            assertEquals("sb-race-1", f.get(5, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertEquals(1, repository.findAll().size(), "并发申请绝不能产生重复环境");
        assertEquals(1, quotaService.used("team-a"), "只能占用一个配额");
        assertEquals(SandboxState.READY, repository.findById("sb-race-1").orElseThrow().getState());
        log.info("   ↳ {} 个并发申请收敛到一个沙箱，used={}", threads, quotaService.used("team-a"));
    }

    @Test
    @DisplayName("续期与回收同时发生：结论唯一，最终必为 RECYCLED，配额恰好归还一次")
    void shouldResolveRenewVsRecycleRace() throws Exception {
        int rounds = 30;
        for (int round = 0; round < rounds; round++) {
            String requestId = "renew-recycle-" + round;
            Sandbox sb = service.apply(requestId, "team-a", "image=x", Duration.ofMinutes(10));

            int threads = 8;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger renewed = new AtomicInteger();
            AtomicInteger recycled = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();
            List<Future<?>> futures = new java.util.ArrayList<>();

            for (int i = 0; i < threads; i++) {
                boolean renew = i % 2 == 0;
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                        try {
                            if (renew) {
                                service.renew(sb.getId(), "team-a", Duration.ofMinutes(1));
                                renewed.incrementAndGet();
                            } else {
                                service.recycle(sb.getId(), "team-a");
                                recycled.incrementAndGet();
                            }
                        } catch (SandboxException ex) {
                            if (ex.getErrorCode() == ErrorCode.ILLEGAL_STATE_TRANSITION) {
                                rejected.incrementAndGet();
                            } else {
                                throw new CompletionException(ex);
                            }
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new CompletionException(ie);
                    }
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(5, TimeUnit.SECONDS);
            }
            pool.shutdown();

            // 4 个回收调用全部幂等返回（已回收的重复回收不抛错、不重复归还）
            assertEquals(4, recycled.get(), "回收调用均幂等返回，round=" + round);
            assertEquals(SandboxState.RECYCLED, sandbox(sb.getId()).getState(), "round=" + round);
            assertEquals(0, quotaService.used("team-a"),
                    "无论续期/回收如何交错，配额必须恰好归还一次，round=" + round);
            assertEquals(4, renewed.get() + rejected.get(),
                    "4 个续期操作：要么成功于回收前，要么因 RECYCLED 被拒绝，round=" + round);
        }
        log.info("   ↳ 续期/回收竞争 {} 轮：状态始终一致，配额无重复/遗漏归还", rounds);
    }

    @Test
    @DisplayName("配额抢占：4 个归属方并发抢 3 个配额，恰好 3 成功 1 被拒，无超配")
    void shouldEnforceQuotaUnderContention() throws Exception {
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < threads; i++) {
            int idx = i;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    try {
                        service.apply("grab-" + idx, "team-a", "image=x;delayMillis=50",
                                Duration.ofMinutes(10));
                        success.incrementAndGet();
                    } catch (SandboxException ex) {
                        if (ex.getErrorCode() == ErrorCode.QUOTA_EXCEEDED) {
                            rejected.incrementAndGet();
                        } else {
                            throw new CompletionException(ex);
                        }
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new CompletionException(ie);
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(5, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(3, success.get());
        assertEquals(1, rejected.get());
        assertEquals(3, quotaService.used("team-a"), "绝不能出现超配");
        assertEquals(3, repository.findAll().size());
        log.info("   ↳ {} 个并发申请：成功 {}，配额拒绝 {}，used={}",
                threads, success.get(), rejected.get(), quotaService.used("team-a"));
    }

    @Test
    @DisplayName("过期回收：TTL 到期后扫描回收，配额归还；未过期者保留")
    void shouldReapExpiredSandboxes() throws Exception {
        Sandbox shortLived = service.apply("exp-1", "team-a", "image=x", Duration.ofSeconds(1));
        Sandbox longLived = service.apply("exp-2", "team-a", "image=x", Duration.ofSeconds(300));
        Sandbox otherTeam = service.apply("exp-3", "team-b", "image=x", Duration.ofSeconds(1));
        assertEquals(2, quotaService.used("team-a"));

        Thread.sleep(1100);
        int reaped = service.recycleExpired();

        assertEquals(2, reaped, "两个 1 秒 TTL 的沙箱都应过期");
        assertEquals(SandboxState.RECYCLED, shortLived.getState());
        assertEquals(SandboxState.RECYCLED, otherTeam.getState());
        assertEquals(SandboxState.READY, longLived.getState(), "未到期沙箱保留");
        assertEquals(1, quotaService.used("team-a"));
        assertEquals(0, quotaService.used("team-b"));

        int secondRun = service.recycleExpired();
        assertEquals(0, secondRun, "重复扫描不会重复回收/重复归还");
        assertEquals(1, quotaService.used("team-a"));
        log.info("   ↳ 过期回收 {} 个，重复扫描回收 {} 个，team-a.used={}",
                reaped, secondRun, quotaService.used("team-a"));
    }
}
