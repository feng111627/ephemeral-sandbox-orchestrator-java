package com.github.feng111627.ephemeralsandboxorchestratorjava.service;

import com.github.feng111627.ephemeralsandboxorchestratorjava.domain.Sandbox;
import com.github.feng111627.ephemeralsandboxorchestratorjava.domain.SandboxErrorCode;
import com.github.feng111627.ephemeralsandboxorchestratorjava.domain.SandboxStatus;
import com.github.feng111627.ephemeralsandboxorchestratorjava.exception.SandboxException;
import com.github.feng111627.ephemeralsandboxorchestratorjava.provision.Provisioner;
import com.github.feng111627.ephemeralsandboxorchestratorjava.quota.QuotaManager;
import com.github.feng111627.ephemeralsandboxorchestratorjava.statemachine.StateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 沙箱申请、续期、回收核心服务。
 *
 * 并发模型：分段锁（按 key 哈希分桶），同一幂等键 / 同一沙箱 id 的操作串行化，
 * 保证续期、人工回收与到期自动回收等并发操作结果一致、幂等重放不产生重复环境。
 *
 * 有效期策略：申请 TTL 必须为正数且不超过可配置上限 {@code sandbox.ttl.max-seconds}；
 * 续期后的绝对到期时间不得超过 now + 上限；已过期环境的续期请求会惰性触发自动回收。
 */
@Service
public class SandboxService {

    private static final Logger log = LoggerFactory.getLogger(SandboxService.class);
    private static final int LOCK_STRIPES = 64;

    private final StateMachine stateMachine;
    private final QuotaManager quotaManager;
    private final Provisioner provisioner;
    /** 平台允许的环境最长有效期（申请时长、续期后的绝对到期时间均不得超过该上限）。 */
    private final Duration maxTtl;

    /** 默认最长有效期（未显式配置时使用）。 */
    public static final Duration DEFAULT_MAX_TTL = Duration.ofHours(24);

    /** 沙箱 id -> 沙箱 */
    private final Map<String, Sandbox> sandboxes = new ConcurrentHashMap<>();
    /** owner + 幂等键 -> 沙箱 id（申请幂等索引） */
    private final Map<String, String> idempotencyIndex = new ConcurrentHashMap<>();
    /** 分段锁 */
    private final ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];

    /** 测试/默认构造：最长有效期取 {@link #DEFAULT_MAX_TTL}。 */
    public SandboxService(StateMachine stateMachine, QuotaManager quotaManager, Provisioner provisioner) {
        this(stateMachine, quotaManager, provisioner, DEFAULT_MAX_TTL);
    }

    @Autowired
    public SandboxService(StateMachine stateMachine, QuotaManager quotaManager, Provisioner provisioner,
                          @Value("${sandbox.ttl.max-seconds:86400}") long maxTtlSeconds) {
        this(stateMachine, quotaManager, provisioner, Duration.ofSeconds(maxTtlSeconds));
    }

    public SandboxService(StateMachine stateMachine, QuotaManager quotaManager, Provisioner provisioner,
                          Duration maxTtl) {
        this.stateMachine = stateMachine;
        this.quotaManager = quotaManager;
        this.provisioner = provisioner;
        this.maxTtl = maxTtl;
        for (int i = 0; i < LOCK_STRIPES; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    /** 配置的最长有效期。 */
    public Duration getMaxTtl() {
        return maxTtl;
    }

    private ReentrantLock lockFor(String key) {
        return locks[Math.floorMod(key.hashCode(), LOCK_STRIPES)];
    }

    /**
     * 申请有效期校验：必须为非空正时长，且不得超过平台允许的最大上限。
     * 校验发生在占用配额 / 创建环境之前，被拒绝时不产生任何占用与半成品。
     */
    private void validateTtl(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new SandboxException(SandboxErrorCode.INVALID_TTL,
                    "ttl must be a positive duration");
        }
        if (ttl.compareTo(maxTtl) > 0) {
            throw new SandboxException(SandboxErrorCode.TTL_LIMIT_EXCEEDED,
                    "ttl %s exceeds max ttl %s".formatted(ttl, maxTtl));
        }
    }

    /**
     * 申请环境（幂等：相同 owner + idempotencyKey 重复提交返回同一环境，不产生重复占用）。
     * 配置失败时补偿：归还配额、置为 FAILED、清除幂等索引以允许安全重试。
     */
    public Sandbox apply(String owner, String idempotencyKey, Duration ttl) {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("owner must not be blank");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        validateTtl(ttl);
        String indexKey = owner + ":" + idempotencyKey;
        ReentrantLock lock = lockFor(indexKey);
        lock.lock();
        try {
            // 幂等重放：直接返回已有环境
            String existingId = idempotencyIndex.get(indexKey);
            if (existingId != null) {
                Sandbox existing = sandboxes.get(existingId);
                if (existing != null) {
                    log.info("idempotent replay: owner={} key={} -> existing sandbox {}", owner, idempotencyKey, existingId);
                    return existing;
                }
            }

            // 配额校验
            if (!quotaManager.tryAcquire(owner)) {
                throw new SandboxException(SandboxErrorCode.QUOTA_EXCEEDED,
                        "owner %s quota exceeded (limit=%d)".formatted(owner, quotaManager.limitOf(owner)));
            }

            Instant now = Instant.now();
            Sandbox sandbox = new Sandbox(UUID.randomUUID().toString(), owner, idempotencyKey,
                    SandboxStatus.REQUESTED, now.plus(ttl), now, now, true);
            sandboxes.put(sandbox.getId(), sandbox);
            idempotencyIndex.put(indexKey, sandbox.getId());
            log.info("sandbox {} requested by owner={} key={}", sandbox.getId(), owner, idempotencyKey);

            // 同步配置；失败时补偿
            provision(sandbox);
            return sandbox;
        } finally {
            lock.unlock();
        }
    }

    private void provision(Sandbox sandbox) {
        stateMachine.checkTransit(sandbox.getStatus(), SandboxStatus.PROVISIONING, sandbox.getId());
        sandbox.setStatus(SandboxStatus.PROVISIONING);
        sandbox.setUpdatedAt(Instant.now());
        try {
            provisioner.provision(sandbox.getId());
            stateMachine.checkTransit(sandbox.getStatus(), SandboxStatus.READY, sandbox.getId());
            sandbox.setStatus(SandboxStatus.READY);
            sandbox.setUpdatedAt(Instant.now());
            log.info("sandbox {} ready", sandbox.getId());
        } catch (RuntimeException e) {
            // 失败补偿：置 FAILED、归还配额、清除幂等索引，允许安全重试
            sandbox.setStatus(SandboxStatus.FAILED);
            sandbox.setUpdatedAt(Instant.now());
            releaseQuotaOnce(sandbox);
            idempotencyIndex.remove(sandbox.getOwner() + ":" + sandbox.getIdempotencyKey(), sandbox.getId());
            log.warn("sandbox {} provision failed, compensated: {}", sandbox.getId(), e.getMessage());
            throw new SandboxException(SandboxErrorCode.PROVISION_FAILED,
                    "sandbox %s provision failed: %s".formatted(sandbox.getId(), e.getMessage()));
        }
    }

    /**
     * 续期（仅归属方可操作，仅 READY 且未过期可续期）。
     * 续期时长必须为正数；续期后的到期时间不得超过 now + 平台最大 TTL，否则整体拒绝（到期时间不变）。
     */
    public Sandbox renew(String sandboxId, String operator, Duration extension) {
        ReentrantLock lock = lockFor(sandboxId);
        lock.lock();
        try {
            Sandbox sandbox = mustGet(sandboxId);
            checkOwnership(sandbox, operator);
            if (extension == null || extension.isZero() || extension.isNegative()) {
                throw new SandboxException(SandboxErrorCode.INVALID_TTL,
                        "extendSeconds must be a positive duration");
            }
            if (sandbox.getStatus() != SandboxStatus.READY) {
                throw new SandboxException(SandboxErrorCode.ILLEGAL_STATE_TRANSITION,
                        "sandbox %s: cannot renew in status %s".formatted(sandboxId, sandbox.getStatus()));
            }
            Instant now = Instant.now();
            if (!sandbox.getExpiresAt().isAfter(now)) {
                // 已过期：惰性触发一次自动回收（与调度扫描同一路径、同一把锁），
                // 环境立即终态化并归还配额，不存在“已过期却仍 READY 占着配额”的悬空窗口；
                // 随后以 SANDBOX_EXPIRED(HTTP 410) 明确告知调用方续期未被接受。
                doRelease(sandbox, "lazy-expiry-on-renew");
                throw new SandboxException(SandboxErrorCode.SANDBOX_EXPIRED,
                        "sandbox %s expired at %s and has been auto-released"
                                .formatted(sandboxId, sandbox.getExpiresAt()));
            }
            Instant newExpiresAt = sandbox.getExpiresAt().plus(extension);
            Instant latestAllowed = now.plus(maxTtl);
            if (newExpiresAt.isAfter(latestAllowed)) {
                throw new SandboxException(SandboxErrorCode.TTL_LIMIT_EXCEEDED,
                        "sandbox %s: renewal would expire at %s, beyond max ttl boundary %s"
                                .formatted(sandboxId, newExpiresAt, latestAllowed));
            }
            sandbox.setExpiresAt(newExpiresAt);
            sandbox.setUpdatedAt(now);
            log.info("sandbox {} renewed by {} until {}", sandboxId, operator, sandbox.getExpiresAt());
            return sandbox;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 回收（仅归属方可操作，幂等：已回收的重复回收直接返回，不重复归还配额）。
     */
    public Sandbox release(String sandboxId, String operator) {
        ReentrantLock lock = lockFor(sandboxId);
        lock.lock();
        try {
            Sandbox sandbox = mustGet(sandboxId);
            checkOwnership(sandbox, operator);
            return doRelease(sandbox, "owner " + operator);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 到期自动回收（平台系统触发，不做归属校验）。
     * 与人工回收、续期走同一把分段锁：同一环境上的操作严格串行，
     * 终态幂等、配额恰好归还一次，结果与人工回收完全一致。
     */
    public Sandbox releaseAutomatically(String sandboxId) {
        ReentrantLock lock = lockFor(sandboxId);
        lock.lock();
        try {
            Sandbox sandbox = mustGet(sandboxId);
            return doRelease(sandbox, "expiry-reaper");
        } finally {
            lock.unlock();
        }
    }

    /**
     * 扫描并回收所有已到期（expiresAt &lt;= now）且仍处于 READY 的环境。
     * 返回本轮被自动回收的环境 id 列表。逐个环境独立加锁，与并发的人工操作互不阻塞。
     */
    public List<String> reapExpiredSandboxes(Instant now) {
        List<String> reapedIds = new ArrayList<>();
        for (Sandbox sandbox : sandboxes.values()) {
            boolean expired = sandbox.getStatus() == SandboxStatus.READY
                    && !sandbox.getExpiresAt().isAfter(now);
            if (!expired) {
                continue;
            }
            try {
                releaseAutomatically(sandbox.getId());
                reapedIds.add(sandbox.getId());
            } catch (SandboxException e) {
                // 并发下环境可能已被人工回收/状态变化：跳过即可，下一轮重新扫描
                log.info("expired sandbox {} skipped by reaper: {}", sandbox.getId(), e.getMessage());
            }
        }
        if (!reapedIds.isEmpty()) {
            log.info("expiry reaper released {} sandbox(es) at {}: {}", reapedIds.size(), now, reapedIds);
        }
        return reapedIds;
    }

    /** 回收共用实现：终态幂等 no-op；否则走 RELEASING -> RELEASED 并归还配额一次。 */
    private Sandbox doRelease(Sandbox sandbox, String actor) {
        String sandboxId = sandbox.getId();
        if (sandbox.getStatus() == SandboxStatus.RELEASED || sandbox.getStatus() == SandboxStatus.FAILED) {
            // 幂等：终态重复回收直接返回（FAILED 的配额已在失败补偿时归还）
            log.info("sandbox {} already in terminal status {}, release is no-op", sandboxId, sandbox.getStatus());
            return sandbox;
        }
        stateMachine.checkTransit(sandbox.getStatus(), SandboxStatus.RELEASING, sandboxId);
        sandbox.setStatus(SandboxStatus.RELEASING);
        sandbox.setUpdatedAt(Instant.now());
        provisioner.destroy(sandboxId);
        stateMachine.checkTransit(SandboxStatus.RELEASING, SandboxStatus.RELEASED, sandboxId);
        sandbox.setStatus(SandboxStatus.RELEASED);
        sandbox.setUpdatedAt(Instant.now());
        releaseQuotaOnce(sandbox);
        log.info("sandbox {} released by {}", sandboxId, actor);
        return sandbox;
    }

    /**
     * 查询环境。
     */
    public Sandbox get(String sandboxId) {
        return mustGet(sandboxId);
    }

    /**
     * 测试辅助：把环境的到期时间拨到指定时刻（不改变状态），
     * 使到期链路可在单测中用极短 TTL 立即验证，无需真实等待。
     */
    public void expireAtForTesting(String sandboxId, Instant expiresAt) {
        Sandbox sandbox = mustGet(sandboxId);
        sandbox.setExpiresAt(expiresAt);
    }

    /**
     * 归属方名下总览：当前在用（READY）与已结束（RELEASED / FAILED）的环境，
     * 以及配额上限、已占用与剩余数量，供申请前判断是否还有额度。
     */
    public OwnerOverview getOverview(String owner) {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("owner must not be blank");
        }
        List<Sandbox> active = new ArrayList<>();
        List<Sandbox> ended = new ArrayList<>();
        for (Sandbox sandbox : sandboxes.values()) {
            if (!owner.equals(sandbox.getOwner())) {
                continue;
            }
            SandboxStatus status = sandbox.getStatus();
            if (status == SandboxStatus.RELEASED || status == SandboxStatus.FAILED) {
                ended.add(sandbox);
            } else {
                active.add(sandbox);
            }
        }
        int limit = quotaManager.limitOf(owner);
        int used = quotaManager.usedOf(owner);
        return new OwnerOverview(owner, active, ended, limit, used, Math.max(0, limit - used));
    }

    private Sandbox mustGet(String sandboxId) {
        Sandbox sandbox = sandboxes.get(sandboxId);
        if (sandbox == null) {
            throw new SandboxException(SandboxErrorCode.SANDBOX_NOT_FOUND, "sandbox %s not found".formatted(sandboxId));
        }
        return sandbox;
    }

    private void checkOwnership(Sandbox sandbox, String operator) {
        if (!sandbox.getOwner().equals(operator)) {
            throw new SandboxException(SandboxErrorCode.NOT_OWNER,
                    "operator %s is not owner of sandbox %s".formatted(operator, sandbox.getId()));
        }
    }

    /** 配额恰好归还一次。 */
    private void releaseQuotaOnce(Sandbox sandbox) {
        if (sandbox.isQuotaHeld()) {
            sandbox.setQuotaHeld(false);
            quotaManager.release(sandbox.getOwner());
        }
    }
}
