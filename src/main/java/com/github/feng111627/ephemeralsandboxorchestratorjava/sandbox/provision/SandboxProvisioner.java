package com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.provision;

import com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.domain.Sandbox;

/**
 * 沙箱底层资源配置器抽象。
 */
public interface SandboxProvisioner {

    ProvisionResult provision(Sandbox sandbox);
}
