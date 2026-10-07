package com.github.feng111627.ephemeralsandboxorchestratorjava.service;

import com.github.feng111627.ephemeralsandboxorchestratorjava.domain.Sandbox;

import java.util.List;

/**
 * 归属方（团队/租户）名下环境总览。
 *
 * @param owner        归属方标识
 * @param active       当前在用的环境（非终态：REQUESTED/PROVISIONING/READY/RELEASING）
 * @param ended        已结束的环境（RELEASED/FAILED）
 * @param quotaLimit   配额上限
 * @param quotaUsed    已占用配额
 * @param quotaRemaining 剩余可用配额
 */
public record OwnerOverview(String owner,
                            List<Sandbox> active,
                            List<Sandbox> ended,
                            int quotaLimit,
                            int quotaUsed,
                            int quotaRemaining) {
}
