package com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.domain;

import com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.exception.ErrorCode;
import com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.exception.SandboxException;

import java.time.Duration;
import java.time.Instant;

/**
 * 临时环境（沙箱）实体。
 *
 * 合法状态迁移：
 * <pre>
 *   PENDING -> READY      配置成功
 *   PENDING -> FAILED     配置失败（随后由服务层补偿释放配额）
 *   READY   -> RECYCLED   主动回收 / 过期回收
 *   FAILED  -> RECYCLED   失败记录的终结归档（配额此前已释放）
 *   RECYCLED -> RECYCLED  回收幂等
 * </pre>
 * 其余迁移一律抛出 {@link ErrorCode#ILLEGAL_STATE_TRANSITION}。
 *
 * 所有迁移方法 synchronized：仓储对同一沙箱加锁 + 实体方法互斥，
 * 保证并发操作（如续期与回收同时发生）结论一致。
 */
public class Sandbox {

    private final String id;
    private final String requestId;
    private final String owner;
    private final String spec;
    private final Instant createdAt;

    private SandboxState state;
    private Instant expiresAt;
    private String failReason;
    /** 是否仍占用归属方配额，CAS 语义保证配额恰好归还一次 */
    private boolean holdsQuota;

    private Sandbox(String id, String requestId, String owner, String spec, Instant createdAt) {
        this.id = id;
        this.requestId = requestId;
        this.owner = owner;
        this.spec = spec;
        this.createdAt = createdAt;
        this.state = SandboxState.PENDING;
        this.holdsQuota = true;
    }

    /** 创建申请中的沙箱，创建即占用一个配额位 */
    public static Sandbox create(String id, String requestId, String owner, String spec, Instant now) {
        return new Sandbox(id, requestId, owner, spec, now);
    }

    public String getId() { return id; }
    public String getRequestId() { return requestId; }
    public String getOwner() { return owner; }
    public String getSpec() { return spec; }
    public Instant getCreatedAt() { return createdAt; }
    public synchronized SandboxState getState() { return state; }
    public synchronized Instant getExpiresAt() { return expiresAt; }
    public synchronized String getFailReason() { return failReason; }
    public synchronized boolean isHoldsQuota() { return holdsQuota; }

    /** PENDING -> READY */
    public synchronized void markReady(Instant expiresAt) {
        requireState(SandboxState.PENDING, "markReady");
        this.expiresAt = expiresAt;
        this.state = SandboxState.READY;
    }

    /** PENDING -> FAILED，记录失败原因 */
    public synchronized void markFailed(String reason) {
        requireState(SandboxState.PENDING, "markFailed");
        this.failReason = reason;
        this.state = SandboxState.FAILED;
    }

    /**
     * 回收：READY -> RECYCLED，或 FAILED/RECYCLED 的幂等归档。
     * @return true 表示本次调用实际完成了回收（READY -> RECYCLED）；
     *         false 表示已经是 RECYCLED，调用方应直接返回现有状态
     */
    public synchronized boolean markRecycled() {
        return switch (state) {
            case READY -> {
                this.state = SandboxState.RECYCLED;
                this.expiresAt = null;
                yield true;
            }
            case FAILED -> {
                this.state = SandboxState.RECYCLED;
                yield true;
            }
            case RECYCLED -> false;
            case PENDING -> throw new SandboxException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "沙箱 " + id + " 仍在配置中(PENDING)，不能回收");
        };
    }

    /** 续期：仅 READY 可续，返回新的到期时间 */
    public synchronized Instant extend(Duration extension, Instant now) {
        if (state != SandboxState.READY) {
            throw new SandboxException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "沙箱 " + id + " 当前状态为 " + state + "，只有 READY 状态可以续期");
        }
        Instant base = expiresAt != null && expiresAt.isAfter(now) ? expiresAt : now;
        this.expiresAt = base.plus(extension);
        return this.expiresAt;
    }

    /** 将到期时间封顶在 ceiling：当前到期时间超过上限时压到上限（仅 READY） */
    public synchronized void capExpiryAt(Instant ceiling) {
        if (state != SandboxState.READY) {
            throw new SandboxException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "沙箱 " + id + " 当前状态为 " + state + "，不能调整到期时间");
        }
        if (expiresAt != null && expiresAt.isAfter(ceiling)) {
            expiresAt = ceiling;
        }
    }

    /**
     * 尝试释放配额占用（幂等 CAS）。
     * @return true 表示本次调用实际归还了一个配额；false 表示此前已归还，不能重复归还
     */
    public synchronized boolean releaseQuotaOnce() {
        if (holdsQuota) {
            holdsQuota = false;
            return true;
        }
        return false;
    }

    public synchronized boolean isExpired(Instant now) {
        return state == SandboxState.READY && expiresAt != null && !now.isBefore(expiresAt);
    }

    private void requireState(SandboxState expected, String action) {
        if (state != expected) {
            throw new SandboxException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "沙箱 " + id + " 当前状态为 " + state + "，" + action + " 要求状态为 " + expected);
        }
    }
}
