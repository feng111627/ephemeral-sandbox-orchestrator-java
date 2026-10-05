package com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.web;

import com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.domain.Sandbox;
import com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.service.SandboxService;
import com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.web.dto.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

/**
 * 临时环境申请/续期/回收的 HTTP 入口。
 */
@RestController
@RequestMapping("/api/sandboxes")
public class SandboxController {

    private final SandboxService sandboxService;

    public SandboxController(SandboxService sandboxService) {
        this.sandboxService = sandboxService;
    }

    /** 申请；申请失败（补偿完成）时以 422 返回，业务结论仍在 body 的 state=FAILED 中 */
    @PostMapping
    public ResponseEntity<SandboxView> apply(@RequestBody ApplyRequest request) {
        Duration ttl = request.ttlSeconds() == null ? null : Duration.ofSeconds(request.ttlSeconds());
        Sandbox sandbox = sandboxService.apply(request.requestId(), request.owner(), request.spec(), ttl);
        HttpStatus status = switch (sandbox.getState()) {
            case READY -> HttpStatus.OK;
            case FAILED -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.OK;
        };
        return ResponseEntity.status(status).body(toView(sandbox));
    }

    @PostMapping("/{id}/renew")
    public SandboxView renew(@PathVariable String id, @RequestBody RenewRequest request) {
        Duration extension = Duration.ofSeconds(
                request.extendSeconds() == null ? 0L : request.extendSeconds());
        return toView(sandboxService.renew(id, request.requester(), extension));
    }

    @PostMapping("/{id}/recycle")
    public SandboxView recycle(@PathVariable String id,
                               @RequestBody(required = false) RequesterRequest request) {
        String requester = request == null ? null : request.requester();
        return toView(sandboxService.recycle(id, requester));
    }

    @GetMapping("/{id}")
    public SandboxView get(@PathVariable String id) {
        return toView(sandboxService.get(id));
    }

    static SandboxView toView(Sandbox s) {
        return new SandboxView(s.getId(), s.getRequestId(), s.getOwner(), s.getSpec(),
                s.getState().name(), s.getCreatedAt(), s.getExpiresAt(),
                s.isHoldsQuota(), s.getFailReason());
    }
}
