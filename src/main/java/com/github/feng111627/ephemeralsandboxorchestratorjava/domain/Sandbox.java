package com.github.feng111627.ephemeralsandboxorchestratorjava.domain;

import java.time.Instant;

/**
 * 沙箱环境实体。
 */
public class Sandbox {
    private final String id;
    private final String owner;
    private final String idempotencyKey;
    private volatile SandboxStatus status;
    private volatile Instant expiresAt;
    /** 平台允许的最晚到期时间（申请时刻 + 最大有效期上限），续期不得越过它。 */
    private final Instant maxExpiresAt;
    private final Instant createdAt;
    private volatile Instant updatedAt;
    /** 是否仍持有配额（用于保证配额恰好归还一次）。 */
    private volatile boolean quotaHeld;

    public Sandbox(String id, String owner, String idempotencyKey, SandboxStatus status,
                   Instant expiresAt, Instant createdAt, Instant updatedAt, boolean quotaHeld) {
        this(id, owner, idempotencyKey, status, expiresAt, expiresAt, createdAt, updatedAt, quotaHeld);
    }

    public Sandbox(String id, String owner, String idempotencyKey, SandboxStatus status,
                   Instant expiresAt, Instant maxExpiresAt, Instant createdAt, Instant updatedAt, boolean quotaHeld) {
        this.id = id;
        this.owner = owner;
        this.idempotencyKey = idempotencyKey;
        this.status = status;
        this.expiresAt = expiresAt;
        this.maxExpiresAt = maxExpiresAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.quotaHeld = quotaHeld;
    }

    public String getId() { return id; }
    public String getOwner() { return owner; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public SandboxStatus getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getMaxExpiresAt() { return maxExpiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public boolean isQuotaHeld() { return quotaHeld; }

    public void setStatus(SandboxStatus status) { this.status = status; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public void setQuotaHeld(boolean quotaHeld) { this.quotaHeld = quotaHeld; }
}
