package com.github.feng111627.ephemeralsandboxorchestratorjava.provision;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地可运行的模拟配置器。
 * 通过 failOnProvision 注入失败的 sandboxId，用于验证失败补偿路径。
 */
@Component
public class SimulatedProvisioner implements Provisioner {

    private static final Logger log = LoggerFactory.getLogger(SimulatedProvisioner.class);

    private final Set<String> failOnProvision = ConcurrentHashMap.newKeySet();
    private final AtomicInteger provisionCalls = new AtomicInteger(0);
    private final AtomicInteger destroyCalls = new AtomicInteger(0);

    @Override
    public void provision(String sandboxId) {
        provisionCalls.incrementAndGet();
        if (failOnProvision.contains(sandboxId)) {
            log.warn("simulated provision failure for sandbox {}", sandboxId);
            throw new RuntimeException("simulated provision failure for " + sandboxId);
        }
        log.info("provisioned sandbox {}", sandboxId);
    }

    @Override
    public void destroy(String sandboxId) {
        destroyCalls.incrementAndGet();
        log.info("destroyed sandbox {}", sandboxId);
    }

    /** 测试辅助：让指定 id 的 provision 失败。 */
    public void failProvisionFor(String sandboxId) {
        failOnProvision.add(sandboxId);
    }

    public int getProvisionCalls() {
        return provisionCalls.get();
    }

    public int getDestroyCalls() {
        return destroyCalls.get();
    }
}
