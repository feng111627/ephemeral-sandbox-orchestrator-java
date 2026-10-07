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
    /** 有效期非法（非正数）或超过平台允许的最大时限 */
    TTL_EXCEEDED
}
