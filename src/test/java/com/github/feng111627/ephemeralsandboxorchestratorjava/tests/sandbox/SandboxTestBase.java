package com.github.feng111627.ephemeralsandboxorchestratorjava.tests.sandbox;

import com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.domain.Sandbox;
import com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.exception.ErrorCode;
import com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.exception.SandboxException;
import com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.provision.SimulatedProvisioner;
import com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.quota.QuotaService;
import com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.repo.InMemorySandboxRepository;
import com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.service.IdempotencyStore;
import com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.service.SandboxService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * 测试基类：
 * - 每个测试类（业务模块）开始时打印一次模块名（取自类上的 @DisplayName）；
 * - 每个用例开始打印用例名，日志可直接看出覆盖了哪些业务、用例及结论。
 */
public abstract class SandboxTestBase {

    protected final Logger log = LoggerFactory.getLogger(getClass());
    private static final Set<Class<?>> LOGGED_MODULES = new HashSet<>();

    protected InMemorySandboxRepository repository;
    protected QuotaService quotaService;
    protected SandboxService service;
    private String caseName;

    @BeforeAll
    static void registerModule() {
        // 触发实例前的占位（真正的模块标题在首次 BeforeEach 用 TestInfo 输出）
    }

    @BeforeEach
    void initService(TestInfo info) {
        synchronized (LOGGED_MODULES) {
            if (LOGGED_MODULES.add(getClass())) {
                String module = info.getTestClass()
                        .map(c -> c.isAnnotationPresent(org.junit.jupiter.api.DisplayName.class)
                                ? c.getAnnotation(org.junit.jupiter.api.DisplayName.class).value()
                                : c.getSimpleName())
                        .orElse(getClass().getSimpleName());
                log.info("########## 业务模块【{}】开始 ##########", module);
            }
        }
        repository = new InMemorySandboxRepository();
        quotaService = new QuotaService(3);
        service = new SandboxService(repository, quotaService, new SimulatedProvisioner(),
                new IdempotencyStore(), 1800L, 7200L);
        caseName = info.getDisplayName();
        log.info("════ 用例开始 | {}", caseName);
    }

    @org.junit.jupiter.api.AfterEach
    void finishCase() {
        log.info("════ 用例结束 | {}", caseName);
    }

    protected Sandbox sandbox(String id) {
        return repository.findById(id).orElseThrow();
    }

    protected SandboxException expectSandboxException(ErrorCode code, Runnable action) {
        try {
            action.run();
        } catch (SandboxException ex) {
            if (ex.getErrorCode() != code) {
                throw new AssertionError("期望错误码 " + code + "，实际 " + ex.getErrorCode(), ex);
            }
            log.info("   ↳ 已按预期拒绝，错误码={}，原因={}", code, ex.getMessage());
            return ex;
        }
        throw new AssertionError("期望抛出 SandboxException(" + code + ")，但未抛出");
    }
}
