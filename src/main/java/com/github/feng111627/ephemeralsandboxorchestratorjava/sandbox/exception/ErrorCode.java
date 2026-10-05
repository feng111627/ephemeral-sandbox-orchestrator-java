package com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.exception;

/**
 * 可区分的业务拒绝原因。
 */
public enum ErrorCode {
    VALIDATION_FAILED("请求参数校验失败"),
    SANDBOX_NOT_FOUND("沙箱不存在"),
    QUOTA_EXCEEDED("超出归属方配额"),
    NOT_OWNER("非归属方无权操作该沙箱"),
    ILLEGAL_STATE_TRANSITION("非法状态迁移"),
    IDEMPOTENCY_CONFLICT("同一 requestId 提交了不一致的请求参数");

    private final String message;

    ErrorCode(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}
