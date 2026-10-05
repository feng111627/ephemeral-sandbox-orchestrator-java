package com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.domain;

import java.util.List;
import java.util.Optional;

/** 沙箱仓储抽象（骨架） */
public interface SandboxRepository {

    void save(Sandbox sandbox);

    Optional<Sandbox> findById(String id);

    Optional<Sandbox> findByRequestId(String requestId);

    List<Sandbox> findAll();
}
