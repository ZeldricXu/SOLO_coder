package com.enterprise.risk.gateway.ratelimit;

import com.enterprise.risk.common.event.RiskEvent;
import com.enterprise.risk.common.exception.RateLimitExceededException;
import com.enterprise.risk.gateway.deserializer.RiskEventDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP请求限流拦截器
 * 在Filter之后、Controller之前执行
 * 负责：解析请求体 -> 执行业务线和实体ID维度的限流
 *
 * 限流维度：
 * 1. 按业务线条限流（需要解析business_line）
 * 2. 按实体ID限流（需要解析entity_id）
 *
 * 注：全局和IP维度限流已在RateLimitFilter中执行
 */
@Slf4j
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimitService rateLimitService;
    private final RiskEventDeserializer eventDeserializer;
    private final ObjectMapper objectMapper;

    public RateLimitInterceptor(RateLimitService rateLimitService,
                                RiskEventDeserializer eventDeserializer,
                                ObjectMapper objectMapper) {
        this.rateLimitService = rateLimitService;
        this.eventDeserializer = eventDeserializer;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        try {
            RiskEvent event = parseEventFromRequest(request);
            if (event != null) {
                rateLimitService.checkBusinessLineLimit(event.getBusinessLine());
                rateLimitService.checkEntityLimit(event.getEntityId());
            }
            return true;

        } catch (RateLimitExceededException e) {
            handleRateLimitExceeded(response, e);
            return false;
        } catch (Exception e) {
            log.warn("限流拦截器解析请求体失败，跳过细粒度限流, path: {}", request.getRequestURI(), e);
            return true;
        }
    }

    /**
     * 从请求中解析事件对象
     * 支持从ContentCachingRequestWrapper中读取缓存的请求体
     */
    private RiskEvent parseEventFromRequest(HttpServletRequest request) {
        try {
            byte[] body = extractRequestBody(request);
            if (body == null || body.length == 0) {
                return null;
            }

            String contentType = request.getContentType();
            if (contentType != null && contentType.contains("protobuf")) {
                return eventDeserializer.deserializeProtobuf(body);
            } else {
                return eventDeserializer.deserializeJson(new String(body));
            }

        } catch (Exception e) {
            log.debug("解析请求体用于限流检查失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 提取请求体字节数组
     */
    private byte[] extractRequestBody(HttpServletRequest request) throws IOException {
        if (request instanceof ContentCachingRequestWrapper wrapper) {
            byte[] cached = wrapper.getContentAsByteArray();
            if (cached.length > 0) {
                return cached;
            }
        }

        try {
            return request.getInputStream().readAllBytes();
        } catch (IllegalStateException e) {
            log.debug("请求流已被读取，无法再次获取: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 处理限流超限异常
     */
    private void handleRateLimitExceeded(HttpServletResponse response, RateLimitExceededException e) throws IOException {
        log.warn("限流拦截器触发, 维度: {}, 信息: {}", e.getDetails(), e.getMessage());

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
}
