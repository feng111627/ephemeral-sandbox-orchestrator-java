package com.github.feng111627.ephemeralsandboxorchestratorjava.tests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 业务覆盖：端到端（真实 HTTP 接口）—— 从申请、查询、续期，一直到到期后被平台自动回收，
 * 再验证配额恢复与归属方总览；另外覆盖非法有效期与续期越界的接口拒绝语义。
 *
 * <p>使用很短的有效期（1~2 秒）与默认 1 秒扫描间隔，单测内即可走完整条链路，无需长时间等待。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "sandbox.quota.default-limit=2",
                "sandbox.ttl.max-seconds=5",
                "sandbox.reaper.enabled=true",
                "sandbox.reaper.fixed-delay-ms=200"
        })
class EndToEndApiTest {

    private static final Logger log = LoggerFactory.getLogger(EndToEndApiTest.class);

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private String base() {
        return "http://localhost:" + port + "/api/sandboxes";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<Map> resp) {
        return resp.getBody();
    }

    @Test
    @DisplayName("端到端: 申请→查询→续期→到期自动回收→总览与配额恢复（真实 HTTP）")
    void applyUseRenewThenAutoReapedOverHttp() throws Exception {
        String owner = "e2e-team";
        String key = "e2e-" + System.nanoTime();

        // 1) 申请一个 1 秒的短 TTL 环境
        ResponseEntity<Map> created = rest.postForEntity(base(),
                Map.of("owner", owner, "idempotencyKey", key, "ttlSeconds", 1), Map.class);
        assertEquals(HttpStatus.OK, created.getStatusCode(), body(created).toString());
        Map<String, Object> sandbox = body(created);
        String id = (String) sandbox.get("id");
        assertNotNull(id);
        assertEquals("READY", sandbox.get("status"));
        log.info("[业务: 端到端] 申请成功 id={} ttl=1s", id);

        // 2) GET 查询
        ResponseEntity<Map> got = rest.getForEntity(base() + "/" + id, Map.class);
        assertEquals(HttpStatus.OK, got.getStatusCode());
        assertEquals("READY", body(got).get("status"));

        // 3) 续期 2 秒（上限 5 秒内），到期时间延后
        ResponseEntity<Map> renewed = rest.postForEntity(base() + "/" + id + "/renew",
                Map.of("operator", owner, "extendSeconds", 2), Map.class);
        assertEquals(HttpStatus.OK, renewed.getStatusCode(), body(renewed).toString());
        log.info("[业务: 端到端] 续期成功, expiresAt={}", body(renewed).get("expiresAt"));

        // 4) 归属方总览：此时仍在用，占 1 / 上限 2，剩 1
        ResponseEntity<Map> overviewBefore = rest.getForEntity(
                base() + "?owner=" + owner, Map.class);
        assertEquals(HttpStatus.OK, overviewBefore.getStatusCode());
        Map<String, Object> ov1 = body(overviewBefore);
        assertEquals(1, ((java.util.List<?>) ov1.get("active")).size());
        assertEquals(0, ((java.util.List<?>) ov1.get("ended")).size());
        assertEquals(1, ov1.get("usedQuota"));
        assertEquals(2, ov1.get("quotaLimit"));
        assertEquals(1, ov1.get("remainingQuota"));

        // 5) 等待平台自动回收（TTL 已续到 ~3 秒，扫描间隔 200ms）
        String finalStatus = waitForStatus(id, "RELEASED", 8000);
        assertEquals("RELEASED", finalStatus, "到期后平台必须自动回收");
        log.info("[业务: 端到端] 到期后平台已自动回收 id={}", id);

        // 6) 回收后查询：状态稳定可预期，为 RELEASED
        ResponseEntity<Map> after = rest.getForEntity(base() + "/" + id, Map.class);
        assertEquals("RELEASED", body(after).get("status"));

        // 7) 回收后续期被明确拒绝（409 ILLEGAL_STATE_TRANSITION）
        ResponseEntity<Map> renewAfter = rest.postForEntity(base() + "/" + id + "/renew",
                Map.of("operator", owner, "extendSeconds", 1), Map.class);
        assertEquals(HttpStatus.CONFLICT, renewAfter.getStatusCode());
        assertEquals("ILLEGAL_STATE_TRANSITION", body(renewAfter).get("errorCode"));

        // 8) 人工再回收幂等
        ResponseEntity<Map> releaseAgain = rest.postForEntity(base() + "/" + id + "/release",
                Map.of("operator", owner), Map.class);
        assertEquals(HttpStatus.OK, releaseAgain.getStatusCode());
        assertEquals("RELEASED", body(releaseAgain).get("status"));

        // 9) 总览：进入 ended，配额已归还，剩余恢复满额
        ResponseEntity<Map> overviewAfter = rest.getForEntity(base() + "?owner=" + owner, Map.class);
        Map<String, Object> ov2 = body(overviewAfter);
        assertEquals(0, ((java.util.List<?>) ov2.get("active")).size());
        assertEquals(1, ((java.util.List<?>) ov2.get("ended")).size());
        assertEquals(0, ov2.get("usedQuota"), "自动回收后配额必须归还");
        assertEquals(2, ov2.get("remainingQuota"), "归还后配额重新可用");
        log.info("[业务: 端到端] 全链路完成: ended={} usedQuota={} remaining={}",
                ((java.util.List<?>) ov2.get("ended")).size(), ov2.get("usedQuota"), ov2.get("remainingQuota"));
    }

    @Test
    @DisplayName("端到端: 非法/超上限有效期在接口层被拒（400），不占配额")
    void invalidTtlRejectedOverHttp() {
        // ttlSeconds = 0：BAD_REQUEST
        ResponseEntity<Map> zero = rest.postForEntity(base(),
                Map.of("owner", "e2e-bad", "idempotencyKey", "z", "ttlSeconds", 0), Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, zero.getStatusCode());

        // ttlSeconds > 上限 5：TTL_EXCEEDED
        ResponseEntity<Map> over = rest.postForEntity(base(),
                Map.of("owner", "e2e-bad", "idempotencyKey", "o", "ttlSeconds", 6), Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, over.getStatusCode());
        assertEquals("TTL_EXCEEDED", body(over).get("errorCode"));

        // 未占用任何配额：总览为空、满额
        ResponseEntity<Map> ov = rest.getForEntity(base() + "?owner=e2e-bad", Map.class);
        assertEquals(0, ((java.util.List<?>) body(ov).get("active")).size());
        assertEquals(2, body(ov).get("remainingQuota"));
        log.info("[业务: 端到端] 非法有效期均被 400 拒绝且未占配额: zero={} over={}",
                body(zero).get("errorCode"), body(over).get("errorCode"));
    }

    @Test
    @DisplayName("端到端: 续期越过绝对上限返回 400 TTL_EXCEEDED，到期时间不变")
    void renewBeyondCapRejectedOverHttp() {
        String owner = "e2e-cap";
        ResponseEntity<Map> created = rest.postForEntity(base(),
                Map.of("owner", owner, "idempotencyKey", "c", "ttlSeconds", 1), Map.class);
        String id = (String) body(created).get("id");
        Object expiresBefore = body(created).get("expiresAt");

        // 续 10 秒远超绝对上限 5 秒 -> 400
        ResponseEntity<Map> rejected = rest.postForEntity(base() + "/" + id + "/renew",
                Map.of("operator", owner, "extendSeconds", 10), Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatusCode());
        assertEquals("TTL_EXCEEDED", body(rejected).get("errorCode"));

        ResponseEntity<Map> current = rest.getForEntity(base() + "/" + id, Map.class);
        assertEquals(expiresBefore, body(current).get("expiresAt"), "被拒续期不得改变到期时间");
        log.info("[业务: 端到端] 越界续期被拒, expiresAt 保持 {}", expiresBefore);
    }

    /** 轮询 GET 直到状态变为期望值或超时。 */
    private String waitForStatus(String id, String expected, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String status = null;
        while (System.currentTimeMillis() < deadline) {
            ResponseEntity<Map> resp = rest.getForEntity(base() + "/" + id, Map.class);
            status = (String) body(resp).get("status");
            if (expected.equals(status)) {
                return status;
            }
            Thread.sleep(100);
        }
        return status;
    }
}
