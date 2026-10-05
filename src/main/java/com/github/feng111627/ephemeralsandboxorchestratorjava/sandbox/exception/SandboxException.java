package com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.exception;

/**
 * 带明确错误码的业务异常，便于区分拒绝原因。
 */
public class SandboxException extends RuntimeException {

    private final ErrorCode errorCode;

    public SandboxException(ErrorCode errorCode, String detail) {
        super(errorCode.getMessage() + ": " + detail);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
