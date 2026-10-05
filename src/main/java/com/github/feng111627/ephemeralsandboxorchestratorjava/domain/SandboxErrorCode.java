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
    DUPLICATE_REQUEST_CONFLICT
}
