package com.agentforge.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证/RBAC 全链路集成测试（MockMvc，H2 内存库 + Flyway）：
 * 验证码 → 注册 → 登录 → /auth/me → refresh 轮换（旧 refresh 失效）→ 401/403 JSON 契约 →
 * logout 后 access 黑名单 → 短信预留 50300。覆盖 task-1 验收 1~3。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agentforge-t1-auth-it;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "agentforge.auth.email-code-ttl=5m",
        "agentforge.auth.expose-code-in-response=true",
        "agentforge.auth.mail-enabled=false",
        "agentforge.auth.seed-admin-enabled=false"
})
@AutoConfigureMockMvc
class AuthFlowIntegrationTest {

    private static final String PASSWORD = "Passw0rd123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldCompleteAuthLifecycleAndEnforceRbac() throws Exception {
        String email = "it-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        String username = "it" + UUID.randomUUID().toString().substring(0, 8);

        // 1) 发送邮箱验证码：响应返回 devCode（仅 expose-code-in-response=true 时）
        MvcResult codeResult = mockMvc.perform(post("/api/v1/auth/email-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", email, "scene", "REGISTER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.expiresIn").value(300))
                .andReturn();
        JsonNode codeData = read(codeResult).path("data");
        assertThat(codeData.path("devCode").asText()).matches("\\d{6}");
        String registerCode = codeData.path("devCode").asText();

        // 2) 注册：返回双 token + 用户信息
        MockMvcResultAccess register = register(email, username, registerCode);
        String accessToken = register.accessToken;
        String refreshToken = register.refreshToken;
        assertThat(register.userEmail).isEqualTo(email);
        assertThat(register.expiresIn).isEqualTo(7200L);

        // 3) /auth/me：user + roles + permissions + menus（默认 USER 角色）
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.user.email").value(email))
                .andExpect(jsonPath("$.data.roles[0]").value("USER"))
                .andExpect(jsonPath("$.data.permissions").isArray())
                .andExpect(jsonPath("$.data.menus").isArray());

        // 4) 登录（邮箱 + 密码）成功
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("account", email, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn();
        assertThat(read(loginResult).path("data").path("refreshToken").asText()).isNotBlank();

        // 5) refresh 轮换：返回新 token，且旧 refresh 立即失效（40102）
        MvcResult refreshResult = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("refreshToken", refreshToken))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn();
        String rotatedRefresh = read(refreshResult).path("data").path("refreshToken").asText();
        assertThat(rotatedRefresh).isNotEqualTo(refreshToken);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("refreshToken", refreshToken))))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(40102));

        // 6) 未登录访问受保护接口：401 + ApiResponse JSON（code=40100）
        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(40100))
                .andExpect(jsonPath("$.message").isNotEmpty());

        // 7) USER 角色访问 user:write/user:read：403 + ApiResponse JSON（code=40300）
        mockMvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        // 8) audit:read 能力同样被拒绝
        mockMvc.perform(get("/api/v1/login-logs").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        // 9) 短信登录预留：未配置通道返回 50300 sms-not-configured（不是 501）
        mockMvc.perform(post("/api/v1/auth/sms-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("phone", "13800001111"))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(50300))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("sms-not-configured")));

        // 10) logout：吊销 refresh + access jti 拉黑；随后 access 访问 /auth/me 变 401
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("refreshToken", rotatedRefresh))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));

        // 11) 已吊销的 refresh 不可再用
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("refreshToken", rotatedRefresh))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40102));
    }

    @Test
    void shouldRejectWrongVerificationCodeAndBadPassword() throws Exception {
        String email = "it-bad-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        MvcResult codeResult = mockMvc.perform(post("/api/v1/auth/email-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", email, "scene", "REGISTER"))))
                .andExpect(status().isOk())
                .andReturn();
        String realCode = read(codeResult).path("data").path("devCode").asText();
        String wrongCode = "000000".equals(realCode) ? "111111" : "000000";

        // 验证码错误 → 40201
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", email, "username", "bad" + UUID.randomUUID().toString().substring(0, 6),
                                "password", PASSWORD, "code", wrongCode))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40201));

        // 60s 冷却内重复发码 → 40202
        mockMvc.perform(post("/api/v1/auth/email-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", email, "scene", "REGISTER"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(40202));

        // 密码规则（缺数字）→ 40000 参数校验
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", "x" + email, "username", "abcd1234",
                                "password", "onlyletters", "code", "123456"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    private MockMvcResultAccess register(String email, String username, String code) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", email, "username", username,
                                "password", PASSWORD, "code", code))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        JsonNode data = read(result).path("data");
        return new MockMvcResultAccess(
                data.path("accessToken").asText(),
                data.path("refreshToken").asText(),
                data.path("expiresIn").asLong(),
                data.path("user").path("email").asText());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String body(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private record MockMvcResultAccess(String accessToken, String refreshToken, long expiresIn, String userEmail) {
    }
}
