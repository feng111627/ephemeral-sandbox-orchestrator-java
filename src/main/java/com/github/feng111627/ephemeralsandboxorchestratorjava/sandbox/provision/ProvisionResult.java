package com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.provision;

/**
 * 底层资源配置结果（骨架：record 定义）。
 */
public record ProvisionResult(boolean success, String endpoint, String failReason) {

    public static ProvisionResult ok(String endpoint) {
        return new ProvisionResult(true, endpoint, null);
    }

    public static ProvisionResult fail(String reason) {
        return new ProvisionResult(false, null, reason);
    }
}
