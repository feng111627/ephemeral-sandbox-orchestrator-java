package com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.web.dto;

/** 仅带操作人（归属方）的请求体，用于回收；可为空（系统回收时直接 POST 空体） */
public record RequesterRequest(String requester) {
}
