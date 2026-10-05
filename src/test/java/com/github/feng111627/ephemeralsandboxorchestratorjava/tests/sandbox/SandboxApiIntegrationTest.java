package com.github.feng111627.ephemeralsandboxorchestratorjava.tests.sandbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 业务五：HTTP 接口端到端（申请/查询/续期/回收/失败补偿/错误码映射）
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("业务五：HTTP 接口端到端行为")
class SandboxApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("申请 -> 查询 -> 续期 -> 回收 全链路 200，状态依次正确")
    void shouldRunFullLifecycleOverHttp() throws Exception {
        String body = """
                {"requestId":"http-1","owner":"team-a","spec":"image=nginx","ttlSeconds":600}
                """;
        String locationId = "sb-http-1";

        mockMvc.perform(post("/api/sandboxes").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(locationId))
                .andExpect(jsonPath("$.state").value("READY"))
                .andExpect(jsonPath("$.holdsQuota").value(true));

        mockMvc.perform(get("/api/sandboxes/" + locationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("READY"));

        mockMvc.perform(post("/api/sandboxes/" + locationId + "/renew")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requester":"team-a","extendSeconds":300}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("READY"));

        mockMvc.perform(post("/api/sandboxes/" + locationId + "/recycle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requester":"team-a"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RECYCLED"))
                .andExpect(jsonPath("$.holdsQuota").value(false));
    }

    @Test
    @DisplayName("配置失败：HTTP 422 + state=FAILED + failReason，配额不残留")
    void shouldReturn422OnProvisionFailure() throws Exception {
        mockMvc.perform(post("/api/sandboxes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-2","owner":"team-a","spec":"fail=true","ttlSeconds":600}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.state").value("FAILED"))
                .andExpect(jsonPath("$.failReason").isNotEmpty());
    }

    @Test
    @DisplayName("错误码映射：404 / 409(QUOTA_EXCEEDED) / 403(NOT_OWNER) / 409(ILLEGAL_STATE_TRANSITION)")
    void shouldMapErrorCodesToHttpStatus() throws Exception {
        // 404
        mockMvc.perform(get("/api/sandboxes/sb-nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("SANDBOX_NOT_FOUND"));

        // 打满配额
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/sandboxes")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"requestId\":\"http-q-" + i + "\",\"owner\":\"quota-team\","
                            + "\"spec\":\"image=x\",\"ttlSeconds\":600}"))
                    .andExpect(status().isOk());
        }
        // 409 QUOTA_EXCEEDED
        mockMvc.perform(post("/api/sandboxes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-q-x","owner":"quota-team","spec":"image=x","ttlSeconds":600}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("QUOTA_EXCEEDED"));

        // 403 NOT_OWNER
        mockMvc.perform(post("/api/sandboxes/sb-http-q-0/recycle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requester":"other-team"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("NOT_OWNER"));

        // 先回收，再续期 -> 409 ILLEGAL_STATE_TRANSITION
        mockMvc.perform(post("/api/sandboxes/sb-http-q-0/recycle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requester":"quota-team"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/sandboxes/sb-http-q-0/renew")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requester":"quota-team","extendSeconds":60}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ILLEGAL_STATE_TRANSITION"));
    }
}
