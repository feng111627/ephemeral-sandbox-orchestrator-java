package com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.web.dto;

import java.time.Instant;

/** 沙箱视图（骨架） */
public record SandboxView(String id, String requestId, String owner, String spec,
                          String state, Instant createdAt, Instant expiresAt,
                          boolean holdsQuota, String failReason) {
}
