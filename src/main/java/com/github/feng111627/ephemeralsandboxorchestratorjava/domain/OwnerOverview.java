package com.github.feng111627.ephemeralsandboxorchestratorjava.domain;

import java.util.List;

/**
 * 归属方（团队/租户）名下环境总览。
 *
 * @param owner          归属方标识
 * @param active         当前仍在使用的环境（REQUESTED/PROVISIONING/READY/RELEASING）
 * @param ended          已结束的环境（RELEASED/FAILED）
 * @param usedQuota      已占用配额
 * @param quotaLimit     配额上限
 * @param remainingQuota 剩余可用配额
 */
public record OwnerOverview(String owner,
                            List<Sandbox> active,
                            List<Sandbox> ended,
                            int usedQuota,
                            int quotaLimit,
                            int remainingQuota) {
}
