package com.github.feng111627.ephemeralsandboxorchestratorjava.exception;

import com.github.feng111627.ephemeralsandboxorchestratorjava.domain.SandboxErrorCode;

/**
 * 携带可区分错误码的业务异常。
 */
public class SandboxException extends RuntimeException {
    private final SandboxErrorCode code;

    public SandboxException(SandboxErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public SandboxErrorCode getCode() { return code; }
}
