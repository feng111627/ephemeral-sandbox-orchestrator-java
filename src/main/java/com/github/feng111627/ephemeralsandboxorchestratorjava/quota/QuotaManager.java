package com.github.feng111627.ephemeralsandboxorchestratorjava.quota;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 按归属方（团队/租户）维度的配额管理。
 * 计数基于 ConcurrentHashMap + AtomicInteger，占用/归还线程安全。
 */
@Component
public class QuotaManager {

    private final int defaultLimit;
    private final Map<String, Integer> ownerLimits = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> used = new ConcurrentHashMap<>();

    public QuotaManager(@Value("${sandbox.quota.default-limit:3}") int defaultLimit) {
        this.defaultLimit = defaultLimit;
    }

    /**
     * 为指定归属方设置独立配额上限（可选，默认使用 defaultLimit）。
     */
    public void setLimit(String owner, int limit) {
        ownerLimits.put(owner, limit);
    }

    /**
     * 尝试为 owner 占用 1 个配额，成功返回 true，超限返回 false。
     */
    public boolean tryAcquire(String owner) {
        AtomicInteger counter = used.computeIfAbsent(owner, k -> new AtomicInteger(0));
        int limit = limitOf(owner);
        while (true) {
            int current = counter.get();
            if (current >= limit) {
                return false;
            }
            if (counter.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /**
     * 归还 owner 的 1 个配额。调用方需保证不会重复归还（由状态机终态保证）。
     */
    public void release(String owner) {
        AtomicInteger counter = used.get(owner);
        if (counter != null) {
            counter.updateAndGet(v -> Math.max(0, v - 1));
        }
    }

    /**
     * 当前已占用配额数。
     */
    public int usedOf(String owner) {
        AtomicInteger counter = used.get(owner);
        return counter == null ? 0 : counter.get();
    }

    /**
     * owner 的配额上限。
     */
    public int limitOf(String owner) {
        return ownerLimits.getOrDefault(owner, defaultLimit);
    }
}
