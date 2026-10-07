package com.github.feng111627.ephemeralsandboxorchestratorjava.domain;

/**
 * 可区分的业务错误原因。
 */
public enum SandboxErrorCode {
    SANDBOX_NOT_FOUND,
    ILLEGAL_STATE_TRANSITION,
    QUOTA_EXCEEDED,
    NOT_OWNER,
    PROVISION_FAILED,
    DUPLICATE_REQUEST_CONFLICT,
    /** 有效期非法（null、非正数等） */
    INVALID_TTL,
    /** 有效期超过平台允许的最大上限（申请或续期） */
    TTL_LIMIT_EXCEEDED,
    /** 环境已过期，操作（如续期）不被允许 */
    SANDBOX_EXPIRED
}
