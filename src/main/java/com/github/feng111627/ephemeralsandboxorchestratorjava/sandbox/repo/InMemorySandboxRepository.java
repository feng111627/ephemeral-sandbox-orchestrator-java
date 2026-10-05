package com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.repo;

import com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.domain.Sandbox;
import com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.domain.SandboxRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 线程安全的内存仓储：
 * - id 与 requestId 双索引；
 * - 每个沙箱 id 一把锁（{@link #lockFor(String)}），服务层在锁内完成
 *   "读取-状态迁移-保存"，保证同一环境上的并发操作结论一致；
 * - 全部内存态，本地即可运行，进程重启即清空。
 */
@Repository
public class InMemorySandboxRepository implements SandboxRepository {

    private final ConcurrentHashMap<String, Sandbox> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> requestIndex = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    @Override
    public void save(Sandbox sandbox) {
        byId.put(sandbox.getId(), sandbox);
        // requestId 映射在首次落库时建立，后续不允许改写
        requestIndex.putIfAbsent(sandbox.getRequestId(), sandbox.getId());
    }

    @Override
    public Optional<Sandbox> findById(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<Sandbox> findByRequestId(String requestId) {
        String id = requestIndex.get(requestId);
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    @Override
    public List<Sandbox> findAll() {
        return List.copyOf(byId.values());
    }

    /** 返回沙箱 id 对应的锁对象，同一 id 永远拿到同一把锁 */
    public Object lockFor(String sandboxId) {
        return locks.computeIfAbsent(sandboxId, k -> new Object());
    }
}
