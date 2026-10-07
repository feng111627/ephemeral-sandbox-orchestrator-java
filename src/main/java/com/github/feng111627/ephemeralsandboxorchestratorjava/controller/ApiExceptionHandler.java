package com.github.feng111627.ephemeralsandboxorchestratorjava.controller;

import com.github.feng111627.ephemeralsandboxorchestratorjava.domain.SandboxErrorCode;
import com.github.feng111627.ephemeralsandboxorchestratorjava.exception.SandboxException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 统一异常 -> 可区分错误响应（错误码 + 对应 HTTP 状态）。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Map<SandboxErrorCode, HttpStatus> STATUS_MAPPING = Map.of(
            SandboxErrorCode.SANDBOX_NOT_FOUND, HttpStatus.NOT_FOUND,
            SandboxErrorCode.ILLEGAL_STATE_TRANSITION, HttpStatus.CONFLICT,
            SandboxErrorCode.QUOTA_EXCEEDED, HttpStatus.TOO_MANY_REQUESTS,
            SandboxErrorCode.NOT_OWNER, HttpStatus.FORBIDDEN,
            SandboxErrorCode.PROVISION_FAILED, HttpStatus.INTERNAL_SERVER_ERROR,
            SandboxErrorCode.DUPLICATE_REQUEST_CONFLICT, HttpStatus.CONFLICT,
            SandboxErrorCode.TTL_EXCEEDED, HttpStatus.BAD_REQUEST
    );

    @ExceptionHandler(SandboxException.class)
    public ResponseEntity<Map<String, Object>> handle(SandboxException ex) {
        HttpStatus status = STATUS_MAPPING.getOrDefault(ex.getCode(), HttpStatus.INTERNAL_SERVER_ERROR);
        return ResponseEntity.status(status).body(Map.of(
                "errorCode", ex.getCode().name(),
                "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "errorCode", "BAD_REQUEST",
                "message", ex.getMessage()
        ));
    }
}
