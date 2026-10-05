package com.github.feng111627.ephemeralsandboxorchestratorjava.statemachine;

import com.github.feng111627.ephemeralsandboxorchestratorjava.domain.SandboxErrorCode;
import com.github.feng111627.ephemeralsandboxorchestratorjava.domain.SandboxStatus;
import com.github.feng111627.ephemeralsandboxorchestratorjava.exception.SandboxException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 业务覆盖：状态机 —— 合法迁移放行、非法迁移拒绝且原因可区分、终态无出边。
 */
class StateMachineTest {

    private static final Logger log = LoggerFactory.getLogger(StateMachineTest.class);
    private final StateMachine stateMachine = new StateMachine();

    @BeforeEach
    void logCase(TestInfo info) {
        log.info("[业务: 状态机] 执行用例: {}", info.getDisplayName());
    }

    @Test
    @DisplayName("合法迁移: REQUESTED->PROVISIONING->READY->RELEASING->RELEASED 全链路放行")
    void legalHappyPathTransitions() {
        assertTrue(stateMachine.canTransit(SandboxStatus.REQUESTED, SandboxStatus.PROVISIONING));
        assertTrue(stateMachine.canTransit(SandboxStatus.PROVISIONING, SandboxStatus.READY));
        assertTrue(stateMachine.canTransit(SandboxStatus.READY, SandboxStatus.RELEASING));
        assertTrue(stateMachine.canTransit(SandboxStatus.RELEASING, SandboxStatus.RELEASED));
        assertDoesNotThrow(() -> {
            stateMachine.checkTransit(SandboxStatus.REQUESTED, SandboxStatus.PROVISIONING, "s1");
            stateMachine.checkTransit(SandboxStatus.PROVISIONING, SandboxStatus.READY, "s1");
            stateMachine.checkTransit(SandboxStatus.READY, SandboxStatus.RELEASING, "s1");
            stateMachine.checkTransit(SandboxStatus.RELEASING, SandboxStatus.RELEASED, "s1");
        });
    }

    @Test
    @DisplayName("合法迁移: 申请/配置阶段可转入 FAILED（失败补偿路径）")
    void legalFailureTransitions() {
        assertTrue(stateMachine.canTransit(SandboxStatus.REQUESTED, SandboxStatus.FAILED));
        assertTrue(stateMachine.canTransit(SandboxStatus.PROVISIONING, SandboxStatus.FAILED));
        assertTrue(stateMachine.canTransit(SandboxStatus.REQUESTED, SandboxStatus.RELEASING));
        assertTrue(stateMachine.canTransit(SandboxStatus.PROVISIONING, SandboxStatus.RELEASING));
    }

    @Test
    @DisplayName("非法迁移: 终态 RELEASED/FAILED 无任何出边")
    void terminalStatesHaveNoOutgoingTransitions() {
        for (SandboxStatus to : SandboxStatus.values()) {
            assertFalse(stateMachine.canTransit(SandboxStatus.RELEASED, to), "RELEASED -> " + to);
            assertFalse(stateMachine.canTransit(SandboxStatus.FAILED, to), "FAILED -> " + to);
        }
    }

    @Test
    @DisplayName("非法迁移: 跳跃与回退被拒绝，且错误码为 ILLEGAL_STATE_TRANSITION")
    void illegalTransitionsRejectedWithDistinctReason() {
        assertFalse(stateMachine.canTransit(SandboxStatus.REQUESTED, SandboxStatus.READY));
        assertFalse(stateMachine.canTransit(SandboxStatus.READY, SandboxStatus.PROVISIONING));
        assertFalse(stateMachine.canTransit(SandboxStatus.REQUESTED, SandboxStatus.RELEASED));
        SandboxException ex = assertThrows(SandboxException.class,
                () -> stateMachine.checkTransit(SandboxStatus.READY, SandboxStatus.PROVISIONING, "s9"));
        assertEquals(SandboxErrorCode.ILLEGAL_STATE_TRANSITION, ex.getCode());
        assertTrue(ex.getMessage().contains("s9"));
        log.info("[业务: 状态机] 非法迁移拒绝原因可区分: code={}, message={}", ex.getCode(), ex.getMessage());
    }
}
