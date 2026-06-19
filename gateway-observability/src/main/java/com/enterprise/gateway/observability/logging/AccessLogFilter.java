package com.enterprise.gateway.observability.logging;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.data.elasticsearch.core.ReactiveElasticsearchTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Component
public class AccessLogFilter implements GlobalFilter, Ordered {

    private final ReactiveElasticsearchTemplate elasticsearchTemplate;
    private final LogIndexManager logIndexManager;

    public AccessLogFilter(ReactiveElasticsearchTemplate elasticsearchTemplate, LogIndexManager logIndexManager) {
        this.elasticsearchTemplate = elasticsearchTemplate;
        this.logIndexManager = logIndexManager;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long startTime = System.currentTimeMillis();
        return chain.filter(exchange)
                .doOnSuccess(aVoid -> writeAccessLog(exchange, startTime, null))
                .doOnError(throwable -> writeAccessLog(exchange, startTime, throwable));
    }

    private void writeAccessLog(ServerWebExchange exchange, long startTime, Throwable throwable) {
        long duration = System.currentTimeMillis() - startTime;
        HttpStatus status = exchange.getResponse().getStatusCode();
        int statusCode = status != null ? status.value() : 500;

        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        String routeId = route != null ? route.getId() : "unknown";

        Map<String, Object> logEntry = new HashMap<>();
        logEntry.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        logEntry.put("method", exchange.getRequest().getMethod().name());
        logEntry.put("path", exchange.getRequest().getURI().getPath());
        logEntry.put("status", statusCode);
        logEntry.put("duration", duration);
        logEntry.put("clientIp", getClientIp(exchange));
        logEntry.put("userId", exchange.getRequest().getHeaders().getFirst("X-User-Id"));
        logEntry.put("routeId", routeId);
        logEntry.put("requestSize", getRequestSize(exchange));
        logEntry.put("responseSize", getResponseSize(exchange));
        logEntry.put("userAgent", exchange.getRequest().getHeaders().getFirst(HttpHeaders.USER_AGENT));

        if (throwable != null) {
            logEntry.put("error", throwable.getMessage());
            logEntry.put("errorType", throwable.getClass().getSimpleName());
        }

        String indexName = "gateway-access-logs-" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd"));

        logIndexManager.ensureIndexExists(indexName)
                .then(elasticsearchTemplate.save(logEntry, indexName))
                .subscribe();
    }

    private String getClientIp(ServerWebExchange exchange) {
        String xForwardedFor = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        return remoteAddress != null ? remoteAddress.getAddress().getHostAddress() : "unknown";
    }

    private long getRequestSize(ServerWebExchange exchange) {
        String contentLength = exchange.getRequest().getHeaders().getFirst(HttpHeaders.CONTENT_LENGTH);
        return contentLength != null ? Long.parseLong(contentLength) : 0;
    }

    private long getResponseSize(ServerWebExchange exchange) {
        String contentLength = exchange.getResponse().getHeaders().getFirst(HttpHeaders.CONTENT_LENGTH);
        return contentLength != null ? Long.parseLong(contentLength) : 0;
    }

    @Override
    public int getOrder() {
        return 100;
    }
}
