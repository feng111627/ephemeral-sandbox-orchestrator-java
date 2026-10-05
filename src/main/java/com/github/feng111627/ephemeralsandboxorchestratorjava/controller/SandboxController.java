package com.github.feng111627.ephemeralsandboxorchestratorjava.controller;

import com.github.feng111627.ephemeralsandboxorchestratorjava.domain.Sandbox;
import com.github.feng111627.ephemeralsandboxorchestratorjava.service.SandboxService;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

/**
 * 沙箱环境 REST 接口。
 */
@RestController
@RequestMapping("/api/sandboxes")
public class SandboxController {

    private final SandboxService sandboxService;

    public SandboxController(SandboxService sandboxService) {
        this.sandboxService = sandboxService;
    }

    public record ApplyRequest(String owner, String idempotencyKey, long ttlSeconds) {}
    public record RenewRequest(String operator, long extendSeconds) {}
    public record ReleaseRequest(String operator) {}

    @PostMapping
    public Sandbox apply(@RequestBody ApplyRequest request) {
        return sandboxService.apply(request.owner(), request.idempotencyKey(), Duration.ofSeconds(request.ttlSeconds()));
    }

    @PostMapping("/{id}/renew")
    public Sandbox renew(@PathVariable String id, @RequestBody RenewRequest request) {
        return sandboxService.renew(id, request.operator(), Duration.ofSeconds(request.extendSeconds()));
    }

    @PostMapping("/{id}/release")
    public Sandbox release(@PathVariable String id, @RequestBody ReleaseRequest request) {
        return sandboxService.release(id, request.operator());
    }

    @GetMapping("/{id}")
    public Sandbox get(@PathVariable String id) {
        return sandboxService.get(id);
    }
}
