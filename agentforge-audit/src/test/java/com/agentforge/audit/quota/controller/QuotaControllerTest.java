package com.agentforge.audit.quota.controller;

import com.agentforge.audit.quota.QuotaService;
import com.agentforge.audit.quota.api.QuotaGrantView;
import com.agentforge.audit.quota.api.QuotaTierView;
import com.agentforge.common.api.PageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 额度接口的 MVC 契约测试：路径与契约 2.7 节一致，校验统一响应包装、参数绑定与入参校验。
 */
class QuotaControllerTest {

    private QuotaService quotaService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        quotaService = mock(QuotaService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new QuotaController(quotaService)).build();
    }

    @Test
    void listTiersReturnsWrappedTierViews() throws Exception {
        QuotaTierView view = new QuotaTierView(1L, "FREE", "免费版", "默认套餐", 20_000, 200_000,
                10, true, true, 2, LocalDateTime.of(2026, 10, 1, 10, 0), LocalDateTime.of(2026, 10, 1, 10, 0));
        when(quotaService.listTiers()).thenReturn(List.of(view));

        mockMvc.perform(get("/api/v1/quota/tiers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].code").value("FREE"))
                .andExpect(jsonPath("$.data[0].dailyTokenLimit").value(20_000))
                .andExpect(jsonPath("$.data[0].builtin").value(true));
    }

    @Test
    void grantTokensBindsCommandAndReturnsSuccess() throws Exception {
        mockMvc.perform(post("/api/v1/quota/users/7/grant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":500,\"reason\":\"补偿\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(quotaService).grantTokens(eq(7L), eq(500L), eq("补偿"), isNull());
    }

    @Test
    void grantTokensRejectsMissingAmount() throws Exception {
        mockMvc.perform(post("/api/v1/quota/users/7/grant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"补偿\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listGrantsReturnsPagedData() throws Exception {
        QuotaGrantView view = new QuotaGrantView(1L, 100L, "admin", 500L, "补偿", 500_600L,
                LocalDateTime.of(2026, 10, 29, 10, 0));
        when(quotaService.listGrants(7L, 1, 10))
                .thenReturn(PageResult.of(List.of(view), 1, 1, 10));

        mockMvc.perform(get("/api/v1/quota/users/7/grants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].amount").value(500));
    }
}
