package com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.quota;

import com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.exception.ErrorCode;
import com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.exception.SandboxException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 归属方配额服务（内存计数，本地可运行）。
 *
 * - {@link #reserve} 在归属方维度原子 +1，已满则拒绝（QUOTA_EXCEEDED）；
 * - {@link #release} 原子 -1，且不会跌破 0；与沙箱上的 releaseQuotaOnce
 *   配合，保证"回收后配额恰好归还一次，不重复归还、不漏还"；
 * - 各归属方计数相互独立，天然支持团队/租户维度隔离。
 */
@Service
public class QuotaService {

    private final int defaultLimit;
    private final ConcurrentHashMap<String, Integer> usedByOwner = new ConcurrentHashMap<>();

    public QuotaService(@Value("${sandbox.quota.default-limit:3}") int defaultLimit) {
        this.defaultLimit = defaultLimit;
    }

    /** 占用一个配额；超配额抛 QUOTA_EXCEEDED */
    public void reserve(String owner) {
        usedByOwner.compute(owner, (key, current) -> {
            int used = current == null ? 0 : current;
            if (used >= defaultLimit) {
                throw new SandboxException(ErrorCode.QUOTA_EXCEEDED,
                        "归属方 " + owner + " 已用配额 " + used + "/" + defaultLimit);
            }
            return used + 1;
        });
    }

    /**
     * 归还一个配额。
     * @return true 表示计数确实减少；false 表示该归属方计数已为 0（防御性，不允许为负）
     */
    public boolean release(String owner) {
        boolean[] changed = {false};
        usedByOwner.compute(owner, (key, current) -> {
            int used = current == null ? 0 : current;
            if (used <= 0) {
                return 0;
            }
            changed[0] = true;
            return used - 1;
        });
        return changed[0];
    }

    public int used(String owner) {
        return usedByOwner.getOrDefault(owner, 0);
    }

    public int limit(String owner) {
        return defaultLimit;
    }
}
