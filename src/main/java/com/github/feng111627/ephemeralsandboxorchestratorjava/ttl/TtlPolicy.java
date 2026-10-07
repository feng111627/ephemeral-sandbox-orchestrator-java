package com.github.feng111627.ephemeralsandboxorchestratorjava.ttl;

import com.github.feng111627.ephemeralsandboxorchestratorjava.domain.SandboxErrorCode;
import com.github.feng111627.ephemeralsandboxorchestratorjava.exception.SandboxException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 有效期策略：申请 TTL 必须为正数且不超过最大时限；续期不得越过绝对上限。
 *
 * <p>最大有效期上限本地可配：{@code sandbox.ttl.max-seconds}（默认 86400 秒 = 24h）。
 *
 * <p>边界语义（明确可预期）：
 * <ul>
 *   <li>申请：{@code ttl <= 0} 视为非法请求（HTTP 400）；{@code ttl > 上限} 拒绝并返回
 *       {@code TTL_EXCEEDED}（HTTP 400）。两种拒绝都发生在占用配额之前，不留下半成品环境。</li>
 *   <li>续期：续期后的到期时间按 {@code max(当前到期时间, now) + 续期时长} 计算，
 *       一旦越过申请时确定的绝对上限（{@code createdAt + 最大时限}），整笔续期被拒绝
 *       （{@code TTL_EXCEEDED}），到期时间保持不变——不做静默截断。</li>
 * </ul>
 */
@Component
public class TtlPolicy {

    private final long maxTtlSeconds;

    public TtlPolicy(@Value("${sandbox.ttl.max-seconds:86400}") long maxTtlSeconds) {
        if (maxTtlSeconds <= 0) {
            throw new IllegalArgumentException("sandbox.ttl.max-seconds must be positive: " + maxTtlSeconds);
        }
        this.maxTtlSeconds = maxTtlSeconds;
    }

    /** 最大有效期上限。 */
    public Duration maxTtl() {
        return Duration.ofSeconds(maxTtlSeconds);
    }

    /**
     * 校验申请 TTL：必须为正数且不超过上限。
     * 必须在占用任何配额之前调用，拒绝时不会产生任何占用或半成品环境。
     */
    public void validateTtl(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be a positive duration: " + ttl);
        }
        if (ttl.compareTo(maxTtl()) > 0) {
            throw new SandboxException(SandboxErrorCode.TTL_EXCEEDED,
                    "requested ttl %s exceeds maximum allowed ttl %s"
                            .formatted(ttl, maxTtl()));
        }
    }

    /**
     * 计算申请时的最晚到期时间（绝对上限）= now + 最大时限。
     * 每个环境的绝对上限在申请时刻固定，续期只能在其之内延长。
     */
    public Instant maxExpiresAtFrom(Clock clock) {
        return clock.instant().plus(maxTtl());
    }

    /**
     * 校验续期时长本身：必须为正数。
     */
    public void validateExtension(Duration extension) {
        if (extension == null || extension.isZero() || extension.isNegative()) {
            throw new IllegalArgumentException("extendSeconds must be a positive duration: " + extension);
        }
    }

    /**
     * 计算续期后的到期时间；若会越过绝对上限 maxExpiresAt 返回 null（调用方据此拒绝续期）。
     *
     * @param currentExpiresAt 当前到期时间
     * @param maxExpiresAt     平台允许的最晚到期时间（申请时确定）
     * @param extension        续期时长
     * @param clock            时钟（当前已过期时从当前时刻起算）
     */
    public Instant renewedExpiresAt(Instant currentExpiresAt, Instant maxExpiresAt,
                                    Duration extension, Clock clock) {
        Instant now = clock.instant();
        Instant base = currentExpiresAt.isAfter(now) ? currentExpiresAt : now;
        Instant candidate = base.plus(extension);
        return candidate.isAfter(maxExpiresAt) ? null : candidate;
    }
}
