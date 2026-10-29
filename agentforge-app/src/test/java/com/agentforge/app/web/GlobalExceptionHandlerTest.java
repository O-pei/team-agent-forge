package com.agentforge.app.web;

import com.agentforge.common.api.ApiResponse;
import com.agentforge.common.exception.QuotaExceededException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * GlobalExceptionHandlerTest：额度超额异常必须映射为 HTTP 429 与业务码 42901。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void quotaExceededMapsToHttp429() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleQuotaExceeded(new QuotaExceededException("额度已用尽"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        ApiResponse<Void> body = response.getBody();
        assertNotNull(body);
        assertEquals(42901, body.code());
    }

    @Test
    void statusOfMapsQuotaCodesTo429() {
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, GlobalExceptionHandler.statusOf(42901));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, GlobalExceptionHandler.statusOf(42900));
    }
}
