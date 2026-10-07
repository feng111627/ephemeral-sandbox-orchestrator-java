package com.github.feng111627.ephemeralsandboxorchestratorjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 业务覆盖：端到端 —— 通过真实 HTTP 接口完成 申请 -> 使用中查询 -> 到期自动回收 全链路；
 * 同时覆盖非法 TTL 拒绝（不占配额）与归属方总览接口。
 *
 * 使用测试 profile 将扫描间隔调到 200ms、最长 TTL 调到 60s，
 * 秒级有效期即可在单测内观察到平台自动回收，无需长时间等待。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "sandbox.expiry.scan-interval-ms=200",
                "sandbox.ttl.max-seconds=60"
        })
class SandboxLifecycleE2ETest {

    private static final Logger log = LoggerFactory.getLogger(SandboxLifecycleE2ETest.class);

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String api(String path) {
        return "http://localhost:" + port + "/api/sandboxes" + path;
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    @Test
    @DisplayName("端到端: 申请(1s TTL) -> READY 可查 -> 平台自动回收为 RELEASED -> 总览与配额同步")
    void applyThenAutoReapedThroughHttp() throws Exception {
        String owner = "team-e2e";
        String key = "e2e-" + System.nanoTime();

        // 1) 申请：走真实 POST /api/sandboxes
        ResponseEntity<JsonNode> applyResp = restTemplate.postForEntity(api(""),
                new HttpEntity<>(Map.of("owner", owner, "idempotencyKey", key, "ttlSeconds", 1), jsonHeaders()),
                JsonNode.class);
        assertEquals(HttpStatus.OK, applyResp.getStatusCode(), applyResp.toString());
        JsonNode created = applyResp.getBody();
        assertNotNull(created);
        String id = created.get("id").asText();
        assertEquals("READY", created.get("status").asText());
        log.info("[业务: 端到端] 申请成功 id={}, status=READY, expiresAt={}", id, created.get("expiresAt").asText());

        // 2) 使用中：GET 可查询，仍为 READY
        JsonNode inUse = restTemplate.getForObject(api("/" + id), JsonNode.class);
        assertEquals("READY", inUse.get("status").asText());

        // 申请前/后总览：环境出现在用列表，剩余配额 = 3 - 1
        JsonNode overviewBefore = restTemplate.getForObject(api("/overview?owner=" + owner), JsonNode.class);
        assertEquals(1, overviewBefore.get("active").size());
        assertEquals(2, overviewBefore.get("quotaRemaining").asInt());
        log.info("[业务: 端到端] 使用中总览: active={}, quotaRemaining={}",
                overviewBefore.get("active").size(), overviewBefore.get("quotaRemaining").asInt());

        // 3) 等待平台自动回收（扫描间隔 200ms，TTL 1s，最多等 3s）
        String finalStatus = waitForStatus(id, "RELEASED", 3000);
        assertEquals("RELEASED", finalStatus, "到期后平台必须自动回收");
        log.info("[业务: 端到端] 检测到平台自动回收 id={}, status=RELEASED", id);

        // 4) 收尾后状态可查询、可预期；总览进入 ended，配额已归还
        JsonNode after = restTemplate.getForObject(api("/" + id), JsonNode.class);
        assertEquals("RELEASED", after.get("status").asText());
        assertFalse(after.get("quotaHeld").asBoolean(), "自动回收后配额标记必须清除");

        JsonNode overviewAfter = restTemplate.getForObject(api("/overview?owner=" + owner), JsonNode.class);
        assertEquals(0, overviewAfter.get("active").size());
        assertEquals(1, overviewAfter.get("ended").size());
        assertEquals(id, overviewAfter.get("ended").get(0).get("id").asText());
        assertEquals(3, overviewAfter.get("quotaRemaining").asInt(), "自动回收必须归还配额");
        log.info("[业务: 端到端] 回收后总览: active=0, ended=1, quotaRemaining=3");
    }

    @Test
    @DisplayName("端到端: 非法/超限 TTL 经真实接口被拒绝(422)，且不占配额、不留环境")
    void invalidTtlRejectedThroughHttp() {
        // ttlSeconds=0 -> INVALID_TTL 422
        ResponseEntity<JsonNode> zero = restTemplate.postForEntity(api(""),
                new HttpEntity<>(Map.of("owner", "team-e2e-bad", "idempotencyKey", "zero", "ttlSeconds", 0),
                        jsonHeaders()),
                JsonNode.class);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, zero.getStatusCode());
        assertEquals("INVALID_TTL", zero.getBody().get("errorCode").asText());

        // ttlSeconds=61 > max 60 -> TTL_LIMIT_EXCEEDED 422
        ResponseEntity<JsonNode> tooLong = restTemplate.postForEntity(api(""),
                new HttpEntity<>(Map.of("owner", "team-e2e-bad", "idempotencyKey", "long", "ttlSeconds", 61),
                        jsonHeaders()),
                JsonNode.class);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, tooLong.getStatusCode());
        assertEquals("TTL_LIMIT_EXCEEDED", tooLong.getBody().get("errorCode").asText());

        JsonNode overview = restTemplate.getForObject(api("/overview?owner=team-e2e-bad"), JsonNode.class);
        assertEquals(0, overview.get("active").size(), "非法申请不得残留半成品环境");
        assertEquals(3, overview.get("quotaRemaining").asInt(), "非法申请不得占用配额");
        log.info("[业务: 端到端] 非法 TTL 拒绝: 0s -> INVALID_TTL, 61s -> TTL_LIMIT_EXCEEDED, 无配额占用");
    }

    private String waitForStatus(String id, String expected, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String status = null;
        while (System.currentTimeMillis() < deadline) {
            JsonNode node = restTemplate.getForObject(api("/" + id), JsonNode.class);
            status = node.get("status").asText();
            if (expected.equals(status)) {
                return status;
            }
            Thread.sleep(100);
        }
        return status;
    }
}
