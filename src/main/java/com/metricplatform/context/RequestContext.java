package com.metricplatform.context;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Data
@Component
public class RequestContext {

    private static final ThreadLocal<RequestInfo> requestHolder = new ThreadLocal<>();

    @Data
    public static class RequestInfo {
        private String clientIp;
        private String userAgent;
        private String user;
        private String requestId;
        private Map<String, Object> attributes = new HashMap<>();
    }

    public static void setRequestInfo(RequestInfo info) {
        requestHolder.set(info);
    }

    public static RequestInfo getRequestInfo() {
        return requestHolder.get();
    }

    public static void clear() {
        requestHolder.remove();
    }

    public static Optional<String> getCurrentUser() {
        RequestInfo info = requestHolder.get();
        return Optional.ofNullable(info != null ? info.getUser() : null);
    }

    public static Optional<String> getClientIp() {
        RequestInfo info = requestHolder.get();
        return Optional.ofNullable(info != null ? info.getClientIp() : null);
    }

    public static Optional<String> getUserAgent() {
        RequestInfo info = requestHolder.get();
        return Optional.ofNullable(info != null ? info.getUserAgent() : null);
    }

    public static Mono<Void> fillContext(ServerWebExchange exchange) {
        return Mono.fromRunnable(() -> {
            RequestInfo info = new RequestInfo();
            info.setClientIp(exchange.getRequest().getRemoteAddress() != null
                    ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                    : "unknown");
            info.setUserAgent(exchange.getRequest().getHeaders().getFirst("User-Agent"));
            info.setUser((String) exchange.getAttributes().get("user"));
            info.setRequestId(exchange.getRequest().getId());
            setRequestInfo(info);
        });
    }
}
