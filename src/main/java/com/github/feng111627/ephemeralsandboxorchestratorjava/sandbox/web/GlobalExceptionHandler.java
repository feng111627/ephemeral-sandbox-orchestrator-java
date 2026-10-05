package com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.web;

import com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.exception.ErrorCode;
import com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.exception.SandboxException;
import com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.web.dto.ErrorView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 业务错误码 -> HTTP 状态映射，拒绝原因可区分、可复现。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(SandboxException.class)
    public ResponseEntity<ErrorView> handle(SandboxException ex) {
        HttpStatus status = switch (ex.getErrorCode()) {
            case SANDBOX_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case QUOTA_EXCEEDED, IDEMPOTENCY_CONFLICT -> HttpStatus.CONFLICT;
            case VALIDATION_FAILED -> HttpStatus.BAD_REQUEST;
            case NOT_OWNER -> HttpStatus.FORBIDDEN;
            case ILLEGAL_STATE_TRANSITION -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status)
                .body(new ErrorView(ex.getErrorCode().name(), ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorView> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(new ErrorView(ErrorCode.VALIDATION_FAILED.name(), "请求体无法解析: " + ex.getMessage()));
    }
}
