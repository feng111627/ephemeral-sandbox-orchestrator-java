package com.github.feng111627.ephemeralsandboxorchestratorjava.service;

import com.github.feng111627.ephemeralsandboxorchestratorjava.domain.OwnerOverview;
import com.github.feng111627.ephemeralsandboxorchestratorjava.domain.Sandbox;
import com.github.feng111627.ephemeralsandboxorchestratorjava.domain.SandboxErrorCode;
import com.github.feng111627.ephemeralsandboxorchestratorjava.domain.SandboxStatus;
import com.github.feng111627.ephemeralsandboxorchestratorjava.exception.SandboxException;
import com.github.feng111627.ephemeralsandboxorchestratorjava.provision.Provisioner;
import com.github.feng111627.ephemeralsandboxorchestratorjava.quota.QuotaManager;
import com.github.feng111627.ephemeralsandboxorchestratorjava.statemachine.StateMachine;
import com.github.feng111627.ephemeralsandboxorchestratorjava.ttl.TtlPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
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
 * 保证续期与回收等并发操作结果一致、幂等重放不产生重复环境。
 */
@Service
public class SandboxService {

    private static final Logger log = LoggerFactory.getLogger(SandboxService.class);
    private static final int LOCK_STRIPES = 64;

    private final StateMachine stateMachine;
    private final QuotaManager quotaManager;
    private final Provisioner provisioner;
    private final TtlPolicy ttlPolicy;
    /** 可替换时钟，测试里用很短的有效期即可驱动到期链路。 */
    private final Clock clock;

    /** 沙箱 id -> 沙箱 */
    private final Map<String, Sandbox> sandboxes = new ConcurrentHashMap<>();
    /** owner + 幂等键 -> 沙箱 id（申请幂等索引） */
    private final Map<String, String> idempotencyIndex = new ConcurrentHashMap<>();
    /** 分段锁 */
    private final ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];

    /** 兼容旧调用方/单测的构造器：使用默认有效期策略与系统时钟。 */
    public SandboxService(StateMachine stateMachine, QuotaManager quotaManager, Provisioner provisioner) {
        this(stateMachine, quotaManager, provisioner, new TtlPolicy(86400), Clock.systemUTC());
    }

    @Autowired
    public SandboxService(StateMachine stateMachine, QuotaManager quotaManager, Provisioner provisioner,
                          TtlPolicy ttlPolicy) {
        this(stateMachine, quotaManager, provisioner, ttlPolicy, Clock.systemUTC());
    }

    public SandboxService(StateMachine stateMachine, QuotaManager quotaManager, Provisioner provisioner,
                          TtlPolicy ttlPolicy, Clock clock) {
        this.stateMachine = stateMachine;
        this.quotaManager = quotaManager;
        this.provisioner = provisioner;
        this.ttlPolicy = ttlPolicy;
        this.clock = clock;
        for (int i = 0; i < LOCK_STRIPES; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    private ReentrantLock lockFor(String key) {
        return locks[Math.floorMod(key.hashCode(), LOCK_STRIPES)];
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
        // 先校验有效期：非法 TTL 在占用配额之前直接拒绝，不产生任何占用或半成品环境
        ttlPolicy.validateTtl(ttl);
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

            Instant now = clock.instant();
            Instant maxExpiresAt = ttlPolicy.maxExpiresAtFrom(clock);
            Sandbox sandbox = new Sandbox(UUID.randomUUID().toString(), owner, idempotencyKey,
                    SandboxStatus.REQUESTED, now.plus(ttl), maxExpiresAt, now, now, true);
            sandboxes.put(sandbox.getId(), sandbox);
            idempotencyIndex.put(indexKey, sandbox.getId());
            log.info("sandbox {} requested by owner={} key={} ttl={} maxExpiresAt={}",
                    sandbox.getId(), owner, idempotencyKey, ttl, maxExpiresAt);

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
        sandbox.setUpdatedAt(clock.instant());
        try {
            provisioner.provision(sandbox.getId());
            stateMachine.checkTransit(sandbox.getStatus(), SandboxStatus.READY, sandbox.getId());
            sandbox.setStatus(SandboxStatus.READY);
            sandbox.setUpdatedAt(clock.instant());
            log.info("sandbox {} ready", sandbox.getId());
        } catch (RuntimeException e) {
            // 失败补偿：置 FAILED、归还配额、清除幂等索引，允许安全重试
            sandbox.setStatus(SandboxStatus.FAILED);
            sandbox.setUpdatedAt(clock.instant());
            releaseQuotaOnce(sandbox);
            idempotencyIndex.remove(sandbox.getOwner() + ":" + sandbox.getIdempotencyKey(), sandbox.getId());
            log.warn("sandbox {} provision failed, compensated: {}", sandbox.getId(), e.getMessage());
            throw new SandboxException(SandboxErrorCode.PROVISION_FAILED,
                    "sandbox %s provision failed: %s".formatted(sandbox.getId(), e.getMessage()));
        }
    }

    /**
     * 续期（仅归属方可操作，仅 READY 状态可续期）。
     *
     * <p>续期时长必须为正数；续期后的到期时间不得越过申请时确定的绝对上限
     * （createdAt + 平台最大有效期）。越界时整笔续期被拒绝（TTL_EXCEEDED），
     * 到期时间保持不变，不做静默截断。
     */
    public Sandbox renew(String sandboxId, String operator, Duration extension) {
        ttlPolicy.validateExtension(extension);
        ReentrantLock lock = lockFor(sandboxId);
        lock.lock();
        try {
            Sandbox sandbox = mustGet(sandboxId);
            checkOwnership(sandbox, operator);
            if (sandbox.getStatus() != SandboxStatus.READY) {
                throw new SandboxException(SandboxErrorCode.ILLEGAL_STATE_TRANSITION,
                        "sandbox %s: cannot renew in status %s".formatted(sandboxId, sandbox.getStatus()));
            }
            Instant newExpiresAt = ttlPolicy.renewedExpiresAt(sandbox.getExpiresAt(),
                    sandbox.getMaxExpiresAt(), extension, clock);
            if (newExpiresAt == null) {
                throw new SandboxException(SandboxErrorCode.TTL_EXCEEDED,
                        ("sandbox %s: renewal of %s would exceed maximum expiry %s "
                                + "(current expiry %s)").formatted(sandboxId, extension,
                                sandbox.getMaxExpiresAt(), sandbox.getExpiresAt()));
            }
            sandbox.setExpiresAt(newExpiresAt);
            sandbox.setUpdatedAt(clock.instant());
            log.info("sandbox {} renewed by {} until {} (max {})",
                    sandboxId, operator, newExpiresAt, sandbox.getMaxExpiresAt());
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
            return releaseInternal(sandbox, "manual by " + operator);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 回收的共用实现（人工回收与到期自动回收都走这里，调用方必须持有该沙箱的分段锁）。
     *
     * <p>与人工回收/续期的并发一致性由同一把沙箱分段锁保证：
     * 配额通过 {@code quotaHeld} 标记保证恰好归还一次，终态（RELEASED/FAILED）回收是幂等 no-op，
     * 因而自动回收与人工回收同时发生也不会重复还、不会漏还。
     *
     * @param reason 收尾原因（日志可观测），如 "manual by team-a" / "expired"
     */
    private Sandbox releaseInternal(Sandbox sandbox, String reason) {
        if (sandbox.getStatus() == SandboxStatus.RELEASED || sandbox.getStatus() == SandboxStatus.FAILED) {
            log.info("sandbox {} already in terminal status {}, release is no-op",
                    sandbox.getId(), sandbox.getStatus());
            return sandbox;
        }
        stateMachine.checkTransit(sandbox.getStatus(), SandboxStatus.RELEASING, sandbox.getId());
        sandbox.setStatus(SandboxStatus.RELEASING);
        sandbox.setUpdatedAt(clock.instant());
        provisioner.destroy(sandbox.getId());
        stateMachine.checkTransit(SandboxStatus.RELEASING, SandboxStatus.RELEASED, sandbox.getId());
        sandbox.setStatus(SandboxStatus.RELEASED);
        sandbox.setUpdatedAt(clock.instant());
        releaseQuotaOnce(sandbox);
        log.info("sandbox {} released ({})", sandbox.getId(), reason);
        return sandbox;
    }

    /**
     * 到期自动回收：回收所有已过到期时间且仍处于 READY 的环境（系统发起，不做归属校验）。
     * 与人工回收、续期共用同一把沙箱锁与同一套收尾逻辑，结果一致、配额恰好归还一次。
     *
     * @return 本次实际回收的环境数量
     */
    public int reapExpiredSandboxes() {
        Instant now = clock.instant();
        int reaped = 0;
        for (Sandbox sandbox : sandboxes.values()) {
            if (sandbox.getStatus() == SandboxStatus.READY
                    && !sandbox.getExpiresAt().isAfter(now)) {
                ReentrantLock lock = lockFor(sandbox.getId());
                lock.lock();
                try {
                    // 双重检查：持锁后可能已被人工回收，或已被续期延后到未来
                    if (sandbox.getStatus() == SandboxStatus.READY
                            && !sandbox.getExpiresAt().isAfter(clock.instant())) {
                        releaseInternal(sandbox, "expired at " + sandbox.getExpiresAt());
                        reaped++;
                    }
                } finally {
                    lock.unlock();
                }
            }
        }
        if (reaped > 0) {
            log.info("expiry reaper collected {} sandbox(es) at {}", reaped, now);
        }
        return reaped;
    }

    /**
     * 查询环境。
     */
    public Sandbox get(String sandboxId) {
        return mustGet(sandboxId);
    }

    /**
     * 归属方名下总览：当前仍在使用的环境、已结束的环境，以及配额占用/上限/剩余。
     * 用于申请前判断额度是否够用。列表按创建时间排序，返回的是调用时刻的快照。
     */
    public OwnerOverview ownerOverview(String owner) {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("owner must not be blank");
        }
        List<Sandbox> active = new ArrayList<>();
        List<Sandbox> ended = new ArrayList<>();
        for (Sandbox sandbox : sandboxes.values()) {
            if (!owner.equals(sandbox.getOwner())) {
                continue;
            }
            if (sandbox.getStatus() == SandboxStatus.RELEASED || sandbox.getStatus() == SandboxStatus.FAILED) {
                ended.add(sandbox);
            } else {
                active.add(sandbox);
            }
        }
        java.util.Comparator<Sandbox> byCreation = java.util.Comparator
                .comparing(Sandbox::getCreatedAt)
                .thenComparing(Sandbox::getId);
        active.sort(byCreation);
        ended.sort(byCreation);
        int used = quotaManager.usedOf(owner);
        int limit = quotaManager.limitOf(owner);
        return new OwnerOverview(owner, List.copyOf(active), List.copyOf(ended),
                used, limit, Math.max(0, limit - used));
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
