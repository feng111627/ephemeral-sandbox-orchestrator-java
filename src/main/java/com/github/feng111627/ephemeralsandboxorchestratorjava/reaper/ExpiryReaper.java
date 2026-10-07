package com.github.feng111627.ephemeralsandboxorchestratorjava.reaper;

import com.github.feng111627.ephemeralsandboxorchestratorjava.service.SandboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 到期自动回收调度器：按固定间隔扫描并回收已到期的 READY 环境。
 *
 * 回收动作复用 {@link SandboxService#reapExpiredSandboxes(Instant)}，
 * 与人工回收、续期共用同一把分段锁，因此三者并发时结果一致：
 * 环境必然走向 RELEASED、配额恰好归还一次。
 *
 * 扫描间隔通过 sandbox.expiry.scan-interval-ms 配置（默认 1000ms），
 * 本地验证时可设为很小的值（如 100ms），用秒级 TTL 即可走完整条链路。
 */
@Component
public class ExpiryReaper {

    private static final Logger log = LoggerFactory.getLogger(ExpiryReaper.class);

    private final SandboxService sandboxService;
    private volatile Instant lastScanTime;

    public ExpiryReaper(SandboxService sandboxService) {
        this.sandboxService = sandboxService;
    }

    @Scheduled(fixedDelayString = "${sandbox.expiry.scan-interval-ms:1000}")
    public void scan() {
        Instant now = Instant.now();
        lastScanTime = now;
        List<String> reaped = sandboxService.reapExpiredSandboxes(now);
        if (!reaped.isEmpty()) {
            log.info("expiry scan at {} auto-released {} expired sandbox(es): {}", now, reaped.size(), reaped);
        }
    }

    /** 测试/观察用：最近一次扫描时间。 */
    public Instant getLastScanTime() {
        return lastScanTime;
    }
}
