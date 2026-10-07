package com.github.feng111627.ephemeralsandboxorchestratorjava.expiry;

import com.github.feng111627.ephemeralsandboxorchestratorjava.service.SandboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 到期自动回收调度器：周期性扫描并回收已过期的环境。
 *
 * <p>扫描间隔本地可配：{@code sandbox.reaper.fixed-delay-ms}（默认 1000ms）；
 * 可用 {@code sandbox.reaper.enabled=false} 关闭（默认开启）。
 * 实际的回收一致性由 {@link SandboxService#reapExpiredSandboxes()} 保证。
 */
@Component
public class ExpiryReaper {

    private static final Logger log = LoggerFactory.getLogger(ExpiryReaper.class);

    private final SandboxService sandboxService;
    private final boolean enabled;

    public ExpiryReaper(SandboxService sandboxService,
                        @Value("${sandbox.reaper.enabled:true}") boolean enabled) {
        this.sandboxService = sandboxService;
        this.enabled = enabled;
    }

    /** 定时扫描回收：回收逻辑本身幂等，与人工回收/续期并发结果一致。 */
    @Scheduled(fixedDelayString = "${sandbox.reaper.fixed-delay-ms:1000}")
    public void sweep() {
        if (!enabled) {
            return;
        }
        try {
            int reaped = sandboxService.reapExpiredSandboxes();
            if (reaped > 0) {
                log.info("scheduled sweep auto-released {} expired sandbox(es)", reaped);
            }
        } catch (RuntimeException e) {
            // 单轮扫描失败不影响后续轮次
            log.warn("scheduled sweep failed, will retry next round: {}", e.getMessage());
        }
    }

    public boolean isEnabled() {
        return enabled;
    }
}
