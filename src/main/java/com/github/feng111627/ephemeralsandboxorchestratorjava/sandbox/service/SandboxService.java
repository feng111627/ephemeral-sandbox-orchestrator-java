package com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.service;

import com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.domain.Sandbox;
import com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.domain.SandboxRepository;
import com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.exception.ErrorCode;
import com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.exception.SandboxException;
import com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.provision.ProvisionResult;
import com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.provision.SandboxProvisioner;
import com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.quota.QuotaService;
import com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.repo.InMemorySandboxRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 沙箱编排核心服务。
 *
 * 一致性设计：
 * 1. 申请按 requestId 加锁串行化，配合 {@link IdempotencyStore} 原子绑定，
 *    并发/重复提交只会产生一个沙箱；重放返回已有沙箱，参数不一致则判冲突。
 * 2. 续期/回收/查询按 sandboxId 加锁，"读-迁移-存"整体原子，
 *    同一沙箱上的并发操作结论确定，不会状态错乱。
 * 3. 配额与沙箱状态联动：申请先占配额；配置失败标记 FAILED 并立即归还；
 *    READY 回收时归还；归还由 holdsQuota 的 CAS 把关，恰好一次。
 */
@Service
public class SandboxService {

    private final SandboxRepository repository;
    private final QuotaService quotaService;
    private final SandboxProvisioner provisioner;
    private final IdempotencyStore idempotencyStore;

    private final Duration defaultTtl;
    private final Duration maxTtl;

    /** requestId 维度的申请锁，保证同一申请单的并发提交串行进入 */
    private final ConcurrentHashMap<String, Object> requestLocks = new ConcurrentHashMap<>();

    public SandboxService(SandboxRepository repository,
                          QuotaService quotaService,
                          SandboxProvisioner provisioner,
                          IdempotencyStore idempotencyStore,
                          @Value("${sandbox.ttl.default-seconds:1800}") long defaultTtlSeconds,
                          @Value("${sandbox.ttl.max-seconds:7200}") long maxTtlSeconds) {
        this.repository = repository;
        this.quotaService = quotaService;
        this.provisioner = provisioner;
        this.idempotencyStore = idempotencyStore;
        this.defaultTtl = Duration.ofSeconds(defaultTtlSeconds);
        this.maxTtl = Duration.ofSeconds(maxTtlSeconds);
    }

    /**
     * 申请沙箱（同步配置，全部成功后返回 READY）。
     * 失败补偿后返回 FAILED 状态的沙箱（配额已归还），不抛出以保留可观测结论。
     */
    public Sandbox apply(String requestId, String owner, String spec, Duration ttl) {
        validateApply(requestId, owner, spec);
        Duration effectiveTtl = ttl == null ? defaultTtl : ttl;
        if (effectiveTtl.isNegative() || effectiveTtl.isZero()) {
            throw new SandboxException(ErrorCode.VALIDATION_FAILED, "ttl 必须为正数");
        }
        if (effectiveTtl.compareTo(maxTtl) > 0) {
            throw new SandboxException(ErrorCode.VALIDATION_FAILED,
                    "ttl " + effectiveTtl.toSeconds() + "s 超过上限 " + maxTtl.toSeconds() + "s");
        }
        String fingerprint = fingerprint(owner, spec, effectiveTtl);

        Object requestLock = requestLocks.computeIfAbsent(requestId, k -> new Object());
        synchronized (requestLock) {
            // 1) 幂等检查：重放或冲突
            Optional<IdempotencyStore.Record> existing = idempotencyStore.get(requestId);
            if (existing.isPresent()) {
                return replay(existing.get(), fingerprint);
            }

            // 2) 先占配额（失败则什么都没留下，天然安全重试）
            quotaService.reserve(owner);

            // 3) 落库 PENDING（持有配额），再绑定 requestId
            String sandboxId = "sb-" + requestId;
            Sandbox sandbox = Sandbox.create(sandboxId, requestId, owner, spec, Instant.now());
            repository.save(sandbox);
            idempotencyStore.putIfAbsent(requestId, sandboxId, fingerprint);

            // 4) 配置底层资源；失败 -> FAILED + 补偿释放配额
            try {
                ProvisionResult result = provisioner.provision(sandbox);
                if (!result.success()) {
                    compensateFailure(sandbox, result.failReason());
                    return sandbox;
                }
                sandbox.markReady(Instant.now().plus(effectiveTtl));
                repository.save(sandbox);
                return sandbox;
            } catch (RuntimeException ex) {
                compensateFailure(sandbox, "provisioner error: " + ex.getMessage());
                return sandbox;
            }
        }
    }

    /** 续期：仅归属方可操作，仅 READY 可续，结果到期时间被钳制在上限内 */
    public Sandbox renew(String sandboxId, String requester, Duration extension) {
        if (extension == null || extension.isNegative() || extension.isZero()) {
            throw new SandboxException(ErrorCode.VALIDATION_FAILED, "续期时长必须为正数");
        }
        synchronized (lockOf(sandboxId)) {
            Sandbox sandbox = requireSandbox(sandboxId);
            requireOwner(sandbox, requester);

            Instant now = Instant.now();
            sandbox.extend(extension, now);
            sandbox.capExpiryAt(now.plus(maxTtl));
            repository.save(sandbox);
            return sandbox;
        }
    }

    /**
     * 回收：仅归属方可操作（requester 为 null 表示系统回收）。
     * READY -> RECYCLED 时归还配额；已 RECYCLED 直接返回（幂等，不重复归还）；
     * PENDING 拒绝回收（配置尚未结束，原因可区分）。
     */
    public Sandbox recycle(String sandboxId, String requester) {
        synchronized (lockOf(sandboxId)) {
            Sandbox sandbox = requireSandbox(sandboxId);
            if (requester != null) {
                requireOwner(sandbox, requester);
            }
            boolean transitioned = sandbox.markRecycled();
            if (transitioned && sandbox.releaseQuotaOnce()) {
                quotaService.release(sandbox.getOwner());
            }
            repository.save(sandbox);
            return sandbox;
        }
    }

    public Sandbox get(String sandboxId) {
        return requireSandbox(sandboxId);
    }

    /** 扫描并回收所有已过期的 READY 沙箱，返回实际回收数量 */
    public int recycleExpired() {
        Instant now = Instant.now();
        int count = 0;
        for (Sandbox snapshot : List.copyOf(repository.findAll())) {
            String id = snapshot.getId();
            synchronized (lockOf(id)) {
                Sandbox sandbox = repository.findById(id).orElse(null);
                if (sandbox != null && sandbox.isExpired(now)) {
                    sandbox.markRecycled();
                    if (sandbox.releaseQuotaOnce()) {
                        quotaService.release(sandbox.getOwner());
                    }
                    repository.save(sandbox);
                    count++;
                }
            }
        }
        return count;
    }

    // ------------------------------------------------------------------
    // internal helpers
    // ------------------------------------------------------------------

    private Sandbox replay(IdempotencyStore.Record record, String fingerprint) {
        if (!record.fingerprint().equals(fingerprint)) {
            throw new SandboxException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "requestId 已绑定过不同的 owner/spec/ttl 参数");
        }
        return repository.findById(record.sandboxId())
                .orElseThrow(() -> new SandboxException(ErrorCode.SANDBOX_NOT_FOUND,
                        "幂等记录指向的沙箱丢失: " + record.sandboxId()));
    }

    private void compensateFailure(Sandbox sandbox, String reason) {
        sandbox.markFailed(reason);
        if (sandbox.releaseQuotaOnce()) {
            quotaService.release(sandbox.getOwner());
        }
        repository.save(sandbox);
    }

    private Object lockOf(String sandboxId) {
        return ((InMemorySandboxRepository) repository).lockFor(sandboxId);
    }

    private Sandbox requireSandbox(String sandboxId) {
        return repository.findById(sandboxId)
                .orElseThrow(() -> new SandboxException(ErrorCode.SANDBOX_NOT_FOUND,
                        "沙箱不存在: " + sandboxId));
    }

    private void requireOwner(Sandbox sandbox, String requester) {
        if (!sandbox.getOwner().equals(requester)) {
            throw new SandboxException(ErrorCode.NOT_OWNER,
                    "沙箱 " + sandbox.getId() + " 归属 " + sandbox.getOwner() + "，" + requester + " 无权操作");
        }
    }

    private void validateApply(String requestId, String owner, String spec) {
        if (requestId == null || requestId.isBlank()) {
            throw new SandboxException(ErrorCode.VALIDATION_FAILED, "requestId 不能为空");
        }
        if (owner == null || owner.isBlank()) {
            throw new SandboxException(ErrorCode.VALIDATION_FAILED, "owner 不能为空");
        }
        if (spec == null || spec.isBlank()) {
            throw new SandboxException(ErrorCode.VALIDATION_FAILED, "spec 不能为空");
        }
    }

    private String fingerprint(String owner, String spec, Duration ttl) {
        return owner + "|" + spec + "|" + ttl.toSeconds();
    }
}
