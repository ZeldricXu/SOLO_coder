package com.logmanager.gateway;

import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import java.util.Map;

public interface ApiGatewayService {
    Mono<ServerResponse> routeRequest(ServerRequest request);
    void registerRoute(String path, String targetService, String method);
    void removeRoute(String path);
    Map<String, RouteInfo> getRoutes();
    Mono<Map<String, Object>> getGatewayMetrics();

    @lombok.Data
    @lombok.AllArgsConstructor
    class RouteInfo {
        private String path;
        private String targetService;
        private String method;
        private int requestCount;
        private int errorCount;
        private long averageLatencyMs;
    }
}
