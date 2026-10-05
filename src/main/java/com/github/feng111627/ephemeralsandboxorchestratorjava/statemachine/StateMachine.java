package com.github.feng111627.ephemeralsandboxorchestratorjava.statemachine;

import com.github.feng111627.ephemeralsandboxorchestratorjava.domain.SandboxErrorCode;
import com.github.feng111627.ephemeralsandboxorchestratorjava.domain.SandboxStatus;
import com.github.feng111627.ephemeralsandboxorchestratorjava.exception.SandboxException;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * 沙箱状态机：定义并校验合法状态迁移。
 *
 * 合法迁移：
 *   REQUESTED    -> PROVISIONING, FAILED, RELEASING
 *   PROVISIONING -> READY, FAILED, RELEASING
 *   READY        -> RELEASING
 *   RELEASING    -> RELEASED
 *   RELEASED     -> （终态，无出边）
 *   FAILED       -> （终态，无出边）
 */
@Component
public class StateMachine {

    private static final Map<SandboxStatus, Set<SandboxStatus>> TRANSITIONS = new EnumMap<>(SandboxStatus.class);

    static {
        TRANSITIONS.put(SandboxStatus.REQUESTED,
                Set.of(SandboxStatus.PROVISIONING, SandboxStatus.FAILED, SandboxStatus.RELEASING));
        TRANSITIONS.put(SandboxStatus.PROVISIONING,
                Set.of(SandboxStatus.READY, SandboxStatus.FAILED, SandboxStatus.RELEASING));
        TRANSITIONS.put(SandboxStatus.READY,
                Set.of(SandboxStatus.RELEASING));
        TRANSITIONS.put(SandboxStatus.RELEASING,
                Set.of(SandboxStatus.RELEASED));
        TRANSITIONS.put(SandboxStatus.RELEASED, Set.of());
        TRANSITIONS.put(SandboxStatus.FAILED, Set.of());
    }

    /**
     * 判断 from -> to 是否为合法迁移。
     */
    public boolean canTransit(SandboxStatus from, SandboxStatus to) {
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * 校验迁移，非法时抛出携带 ILLEGAL_STATE_TRANSITION 的异常。
     */
    public void checkTransit(SandboxStatus from, SandboxStatus to, String sandboxId) {
        if (!canTransit(from, to)) {
            throw new SandboxException(SandboxErrorCode.ILLEGAL_STATE_TRANSITION,
                    "sandbox %s: illegal transition %s -> %s".formatted(sandboxId, from, to));
        }
    }
}
