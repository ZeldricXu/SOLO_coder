package com.logmanager.gateway;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApiGatewayServiceImpl implements ApiGatewayService {

    private final Map<String, RouteInfo> routes = new ConcurrentHashMap<>();

    @Override
    public Mono<ServerResponse> routeRequest(ServerRequest request) {
        String path = request.path();
        String method = request.methodName();

        RouteInfo route = findMatchingRoute(path, method);
        if (route == null) {
            return ServerResponse.notFound().build();
        }

        long startTime = System.currentTimeMillis();
        route.setRequestCount(route.getRequestCount() + 1);

        log.info("Routing request: {} {} -> {}", method, path, route.getTargetService());

        return ServerResponse.ok()
                .bodyValue(Map.of(
                        "status", "routed",
                        "targetService", route.getTargetService(),
                        "requestId", java.util.UUID.randomUUID().toString(),
                        "timestamp", Instant.now().toString()
                ))
                .doOnSuccess(response -> {
                    long latency = System.currentTimeMillis() - startTime;
                    updateLatency(route, latency);
                })
                .doOnError(error -> {
                    route.setErrorCount(route.getErrorCount() + 1);
                    log.error("Error routing request: {} {}", method, path, error);
                });
    }

    @Override
    public void registerRoute(String path, String targetService, String method) {
        RouteInfo route = new RouteInfo(path, targetService, method, 0, 0, 0);
        routes.put(path + ":" + method, route);
        log.info("Registered route: {} {} -> {}", method, path, targetService);
    }

    @Override
    public void removeRoute(String path) {
        routes.keySet().removeIf(key -> key.startsWith(path + ":"));
        log.info("Removed routes for path: {}", path);
    }

    @Override
    public Map<String, RouteInfo> getRoutes() {
        return new HashMap<>(routes);
    }

    @Override
    public Mono<Map<String, Object>> getGatewayMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("totalRoutes", routes.size());
        metrics.put("totalRequests", routes.values().stream().mapToInt(RouteInfo::getRequestCount).sum());
        metrics.put("totalErrors", routes.values().stream().mapToInt(RouteInfo::getErrorCount).sum());
        metrics.put("averageLatencyMs", routes.values().stream().mapToLong(RouteInfo::getAverageLatencyMs).average().orElse(0));
        metrics.put("routes", routes);
        return Mono.just(metrics);
    }

    private RouteInfo findMatchingRoute(String path, String method) {
        return routes.get(path + ":" + method);
    }

    private void updateLatency(RouteInfo route, long latency) {
        long currentAvg = route.getAverageLatencyMs();
        long newAvg = (currentAvg * (route.getRequestCount() - 1) + latency) / route.getRequestCount();
        route.setAverageLatencyMs(newAvg);
    }
}
