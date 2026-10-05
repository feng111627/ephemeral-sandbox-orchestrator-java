package com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.domain;

/**
 * 沙箱生命周期状态：
 * PENDING（申请中/配置中）-> READY（就绪可用）
 * PENDING -> FAILED（申请失败，已补偿释放）
 * READY / FAILED -> RECYCLED（已回收）
 */
public enum SandboxState {
    PENDING,
    READY,
    FAILED,
    RECYCLED
}
