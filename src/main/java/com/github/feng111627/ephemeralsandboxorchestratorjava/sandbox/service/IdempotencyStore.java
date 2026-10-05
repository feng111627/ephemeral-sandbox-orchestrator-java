package com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.service;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * requestId 幂等记录（内存）。
 *
 * 一条记录绑定：requestId -> (sandboxId, 请求指纹)。
 * - 原子 putIfAbsent 保证同一 requestId 的并发/重复申请只会绑定到同一个沙箱，
 *   不会产生重复环境；
 * - 请求指纹（owner|spec|ttl）用于识别"同一 requestId 但参数不一致"的冲突提交；
 * - 重放直接返回已绑定的沙箱（无论它处于 READY / FAILED / RECYCLED），
 *   不会丢掉已有环境。
 */
@Component
public class IdempotencyStore {

    public record Record(String sandboxId, String fingerprint) {
    }

    private final ConcurrentHashMap<String, Record> records = new ConcurrentHashMap<>();

    /**
     * 原子绑定。
     * @return 已存在的记录（调用方据此重放或判冲突）；不存在则写入并返回 empty
     */
    public Optional<Record> putIfAbsent(String requestId, String sandboxId, String fingerprint) {
        Record fresh = new Record(sandboxId, fingerprint);
        Record existing = records.putIfAbsent(requestId, fresh);
        return Optional.ofNullable(existing);
    }

    public Optional<Record> get(String requestId) {
        return Optional.ofNullable(records.get(requestId));
    }
}
