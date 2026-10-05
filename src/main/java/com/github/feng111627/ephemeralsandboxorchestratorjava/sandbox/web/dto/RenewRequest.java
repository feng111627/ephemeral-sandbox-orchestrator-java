package com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.web.dto;

/** 续期请求体（骨架） */
public record RenewRequest(String requester, Long extendSeconds) {
}
