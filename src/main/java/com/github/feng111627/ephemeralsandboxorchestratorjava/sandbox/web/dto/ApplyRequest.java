package com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.web.dto;

/** 申请请求体（骨架） */
public record ApplyRequest(String requestId, String owner, String spec, Long ttlSeconds) {
}
