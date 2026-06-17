package com.enterprise.risk.gateway.ratelimit;

import com.enterprise.risk.common.exception.RateLimitExceededException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 全局限流过滤器（Spring WebFilter实现）
 * 基于Spring Filter机制，在请求进入Controller之前执行限流检查
 *
 * 过滤顺序：最高优先级（HIGHEST_PRECEDENCE + 100）
 * 限流维度：全局 + IP
 * 注：业务线和实体ID维度的限流在拦截器中执行（需要解析请求体）
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitService rateLimitService;
    private final ObjectMapper objectMapper;

    /**
     * 需要跳过限流检查的路径
     */
    private static final String[] SKIP_PATHS = {
            "/actuator",
            "/health",
            "/metrics",
            "/swagger",
            "/v3/api-docs"
    };

    public RateLimitFilter(RateLimitService rateLimitService, ObjectMapper objectMapper) {
        this.rateLimitService = rateLimitService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String requestPath = request.getRequestURI();

        if (shouldSkip(requestPath)) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            rateLimitService.checkGlobalLimit();

            String clientIp = extractClientIp(request);
            rateLimitService.checkIpLimit(clientIp);

            ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request);
            filterChain.doFilter(wrappedRequest, response);

        } catch (RateLimitExceededException e) {
            handleRateLimitExceeded(response, e);
        }
    }

    /**
     * 处理限流超限异常，返回429 Too Many Requests
     */
    private void handleRateLimitExceeded(HttpServletResponse response, RateLimitExceededException e) throws IOException {
        log.warn("限流过滤器拦截请求, 错误码: {}, 信息: {}", e.getErrorCode(), e.getMessage());

        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Retry-After", "1");

        Map<String, Object> errorBody = new HashMap<>();
        errorBody.put("code", 429);
        errorBody.put("error_code", e.getErrorCode());
        errorBody.put("message", e.getMessage());
        errorBody.put("details", e.getDetails());

        response.getWriter().write(objectMapper.writeValueAsString(errorBody));
    }

    /**
     * 判断是否跳过限流检查
     */
    private boolean shouldSkip(String requestPath) {
        if (requestPath == null) {
            return true;
        }
        for (String skipPath : SKIP_PATHS) {
            if (requestPath.startsWith(skipPath)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从请求中提取客户端IP
     */
    private String extractClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty() && !"unknown".equalsIgnoreCase(xForwardedFor)) {
            int index = xForwardedFor.indexOf(',');
            return index > 0 ? xForwardedFor.substring(0, index).trim() : xForwardedFor.trim();
        }

        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty() && !"unknown".equalsIgnoreCase(xRealIp)) {
            return xRealIp.trim();
        }

        return request.getRemoteAddr();
    }
}
