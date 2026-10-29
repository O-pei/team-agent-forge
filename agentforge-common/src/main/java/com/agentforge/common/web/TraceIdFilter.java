package com.agentforge.common.web;

import com.agentforge.common.context.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 为每个 HTTP 请求建立 TraceId，写入 MDC 与响应头，便于日志聚合。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = TraceContext.currentOrCreate(request.getHeader(TraceContext.TRACE_HEADER));
        TraceContext.setTraceId(traceId);
        MDC.put(TraceContext.TRACE_MDC_KEY, traceId);
        response.setHeader(TraceContext.TRACE_HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(TraceContext.TRACE_MDC_KEY);
            TraceContext.clear();
        }
    }
}
