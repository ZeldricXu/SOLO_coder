package com.taskflow.logging.filter;

import com.taskflow.common.model.Constants;
import com.taskflow.logging.context.LogContext;
import com.taskflow.logging.model.AccessLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AccessLogWebFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        long startTime = System.currentTimeMillis();

        String traceId = exchange.getRequest().getHeaders().getFirst(Constants.TRACE_ID_HEADER);
        if (traceId != null) {
            LogContext.setTraceId(traceId);
        } else {
            traceId = LogContext.getTraceId();
        }

        String tenantId = exchange.getRequest().getHeaders().getFirst(Constants.TENANT_ID_HEADER);
        String userId = exchange.getRequest().getHeaders().getFirst(Constants.USER_ID_HEADER);

        if (tenantId != null) {
            LogContext.setTenantId(tenantId);
        }
        if (userId != null) {
            LogContext.setUserId(userId);
        }

        exchange.getResponse().getHeaders().set(Constants.TRACE_ID_HEADER, traceId);

        String finalTraceId = traceId;
        String finalTenantId = tenantId;
        String finalUserId = userId;
        return chain.filter(exchange)
                .doFinally(signalType -> {
                    long duration = System.currentTimeMillis() - startTime;
                    int status = exchange.getResponse().getStatusCode() != null
                            ? exchange.getResponse().getStatusCode().value()
                            : 200;

                    String clientIp = exchange.getRequest().getRemoteAddress() != null
                            ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                            : "unknown";

                    String userAgent = exchange.getRequest().getHeaders().getFirst("User-Agent");

                    AccessLog accessLog = AccessLog.builder()
                            .method(exchange.getRequest().getMethod().name())
                            .path(exchange.getRequest().getPath().value())
                            .queryString(exchange.getRequest().getURI().getQuery())
                            .status(status)
                            .durationMs(duration)
                            .clientIp(clientIp)
                            .userAgent(userAgent)
                            .traceId(finalTraceId)
                            .tenantId(finalTenantId)
                            .userId(finalUserId)
                            .build();

                    log.info("ACCESS {}", accessLog);
                    LogContext.clear();
                });
    }
}
