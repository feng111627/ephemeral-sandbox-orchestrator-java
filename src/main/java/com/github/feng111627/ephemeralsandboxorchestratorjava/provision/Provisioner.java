package com.github.feng111627.ephemeralsandboxorchestratorjava.provision;

/**
 * 环境配置器：负责实际的环境准备与销毁。
 */
public interface Provisioner {

    /**
     * 配置环境，失败时抛出异常。
     */
    void provision(String sandboxId);

    /**
     * 销毁环境。
     */
    void destroy(String sandboxId);
}
