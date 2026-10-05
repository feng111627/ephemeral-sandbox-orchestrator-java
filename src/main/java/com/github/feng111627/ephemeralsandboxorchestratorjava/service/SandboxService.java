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
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
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

    /** 沙箱 id -> 沙箱 */
    private final Map<String, Sandbox> sandboxes = new ConcurrentHashMap<>();
    /** owner + 幂等键 -> 沙箱 id（申请幂等索引） */
    private final Map<String, String> idempotencyIndex = new ConcurrentHashMap<>();
    /** 分段锁 */
    private final ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];

    public SandboxService(StateMachine stateMachine, QuotaManager quotaManager, Provisioner provisioner) {
        this.stateMachine = stateMachine;
        this.quotaManager = quotaManager;
        this.provisioner = provisioner;
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
     * 续期（仅归属方可操作，仅 READY 状态可续期）。
     */
    public Sandbox renew(String sandboxId, String operator, Duration extension) {
        ReentrantLock lock = lockFor(sandboxId);
        lock.lock();
        try {
            Sandbox sandbox = mustGet(sandboxId);
            checkOwnership(sandbox, operator);
            if (sandbox.getStatus() != SandboxStatus.READY) {
                throw new SandboxException(SandboxErrorCode.ILLEGAL_STATE_TRANSITION,
                        "sandbox %s: cannot renew in status %s".formatted(sandboxId, sandbox.getStatus()));
            }
            Instant now = Instant.now();
            Instant base = sandbox.getExpiresAt().isAfter(now) ? sandbox.getExpiresAt() : now;
            sandbox.setExpiresAt(base.plus(extension));
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
            log.info("sandbox {} released by {}", sandboxId, operator);
            return sandbox;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 查询环境。
     */
    public Sandbox get(String sandboxId) {
        return mustGet(sandboxId);
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
