package com.github.feng111627.ephemeralsandboxorchestratorjava.quota;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 业务覆盖：配额 —— 上限边界、归还、归属方隔离、并发占用不超发。
 */
class QuotaManagerTest {

    private static final Logger log = LoggerFactory.getLogger(QuotaManagerTest.class);
    private QuotaManager quotaManager;

    @BeforeEach
    void setUp(TestInfo info) {
        quotaManager = new QuotaManager(2);
        log.info("[业务: 配额] 执行用例: {}", info.getDisplayName());
    }

    @Test
    @DisplayName("边界: 占用达到上限后第 N+1 次申请被拒绝")
    void acquireUpToLimitThenRejected() {
        assertTrue(quotaManager.tryAcquire("team-a"));
        assertTrue(quotaManager.tryAcquire("team-a"));
        assertFalse(quotaManager.tryAcquire("team-a"), "超过上限必须拒绝");
        assertEquals(2, quotaManager.usedOf("team-a"));
    }

    @Test
    @DisplayName("归还: 归还后可再次占用，且不会归还成负数")
    void releaseAllowsReacquireAndNeverNegative() {
        quotaManager.tryAcquire("team-a");
        quotaManager.release("team-a");
        assertEquals(0, quotaManager.usedOf("team-a"));
        quotaManager.release("team-a");
        assertEquals(0, quotaManager.usedOf("team-a"), "归还不能变成负数");
        assertTrue(quotaManager.tryAcquire("team-a"));
    }

    @Test
    @DisplayName("隔离: 不同归属方配额互不影响，独立上限生效")
    void ownersAreIsolated() {
        quotaManager.setLimit("team-b", 1);
        assertTrue(quotaManager.tryAcquire("team-a"));
        assertTrue(quotaManager.tryAcquire("team-a"));
        assertTrue(quotaManager.tryAcquire("team-b"), "team-b 不受 team-a 占用影响");
        assertFalse(quotaManager.tryAcquire("team-b"), "team-b 独立上限为 1");
    }

    @Test
    @DisplayName("并发: 多线程抢占配额，成功数恰好等于上限，不超发")
    void concurrentAcquireNeverExceedsLimit() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> quotaManager.tryAcquire("team-a"));
        }
        List<Future<Boolean>> results = pool.invokeAll(tasks);
        int succeeded = 0;
        for (Future<Boolean> r : results) {
            if (r.get()) succeeded++;
        }
        pool.shutdown();
        assertEquals(2, succeeded, "并发下成功占用数必须等于上限");
        assertEquals(2, quotaManager.usedOf("team-a"));
        log.info("[业务: 配额] 并发抢占结果: {} 线程争抢, {} 成功, 上限 {}", threads, succeeded, quotaManager.limitOf("team-a"));
    }
}
