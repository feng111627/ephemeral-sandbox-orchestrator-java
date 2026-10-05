package com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 周期性扫描过期的 READY 沙箱并回收，配额随之归还。
 * 间隔由 sandbox.reaper.interval-ms 配置；本地验证时也可直接调用 service。
 */
@Component
public class ExpiredSandboxReaper {

    private static final Logger log = LoggerFactory.getLogger(ExpiredSandboxReaper.class);

    private final SandboxService sandboxService;

    public ExpiredSandboxReaper(SandboxService sandboxService,
                                @Value("${sandbox.reaper.interval-ms:15000}") long ignoredIntervalMs) {
        this.sandboxService = sandboxService;
    }

    @Scheduled(fixedDelayString = "${sandbox.reaper.interval-ms:15000}")
    public void reap() {
        int reaped = sandboxService.recycleExpired();
        if (reaped > 0) {
            log.info("[Reaper] 本次回收过期沙箱 {} 个，配额已归还", reaped);
        }
    }
}
