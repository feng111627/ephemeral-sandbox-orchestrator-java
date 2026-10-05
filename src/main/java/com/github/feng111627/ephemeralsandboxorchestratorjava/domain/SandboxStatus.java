package com.github.feng111627.ephemeralsandboxorchestratorjava.domain;

/**
 * 沙箱环境生命周期状态。
 */
public enum SandboxStatus {
    /** 已申请，等待配置 */
    REQUESTED,
    /** 配置中 */
    PROVISIONING,
    /** 就绪可用 */
    READY,
    /** 回收中 */
    RELEASING,
    /** 已回收（终态） */
    RELEASED,
    /** 申请/配置失败（终态） */
    FAILED
}
