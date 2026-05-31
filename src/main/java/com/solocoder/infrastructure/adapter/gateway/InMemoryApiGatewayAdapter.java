package com.solocoder.infrastructure.adapter.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.solocoder.domain.port.ApiGatewayPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class InMemoryApiGatewayAdapter implements ApiGatewayPort {

    private final Map<String, RouteConfig> routeRegistry = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final WebClient.Builder webClientBuilder;

    public InMemoryApiGatewayAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.webClientBuilder = WebClient.builder();
        initDefaultRoutes();
    }

    private void initDefaultRoutes() {
        routeRegistry.put("/api/v1/storage/**", new RouteConfig(
                "storage-service", "http://localhost:8080", "HTTP"
        ));
        routeRegistry.put("/api/v1/features/**", new RouteConfig(
                "feature-store", "http://localhost:8080", "HTTP"
        ));
        routeRegistry.put("/api/v1/gpu/**", new RouteConfig(
                "gpu-scheduler", "http://localhost:8080", "HTTP"
        ));
        routeRegistry.put("/api/v1/documents/**", new RouteConfig(
                "document-pipeline", "http://localhost:8080", "HTTP"
        ));
    }

    @Override
    public Mono<Map<String, Object>> routeRequest(String path, String method,
                                                    Map<String, String> headers,
                                                    Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            RouteConfig routeConfig = findMatchingRoute(path);
            if (routeConfig == null) {
                throw new RuntimeException("No route found for path: " + path);
            }

            String targetUrl = routeConfig.targetUrl + path;

            WebClient webClient = webClientBuilder.build();
            WebClient.RequestHeadersSpec<?> requestSpec;

            if ("GET".equalsIgnoreCase(method)) {
                requestSpec = webClient.get().uri(targetUrl);
            } else if ("POST".equalsIgnoreCase(method)) {
                requestSpec = webClient.post().uri(targetUrl)
                        .bodyValue(body != null ? body : Map.of());
            } else if ("PUT".equalsIgnoreCase(method)) {
                requestSpec = webClient.put().uri(targetUrl)
                        .bodyValue(body != null ? body : Map.of());
            } else if ("DELETE".equalsIgnoreCase(method)) {
                requestSpec = webClient.delete().uri(targetUrl);
            } else {
                throw new RuntimeException("Unsupported method: " + method);
            }

            if (headers != null) {
                headers.forEach(requestSpec::header);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("route", routeConfig.serviceName);
            response.put("targetUrl", targetUrl);
            response.put("status", "routed");
            response.put("timestamp", System.currentTimeMillis());

            return response;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> registerRoute(String path, String targetService, String protocol) {
        return Mono.fromRunnable(() -> {
            routeRegistry.put(path, new RouteConfig(
                    targetService, "http://localhost:8080", protocol
            ));
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public Mono<Void> removeRoute(String path) {
        return Mono.fromRunnable(() -> routeRegistry.remove(path))
                .subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public Map<String, Object> getRouteConfig(String path) {
        RouteConfig config = findMatchingRoute(path);
        if (config == null) {
            return null;
        }
        Map<String, Object> result = new HashMap<>();
        result.put("serviceName", config.serviceName);
        result.put("targetUrl", config.targetUrl);
        result.put("protocol", config.protocol);
        return result;
    }

    @Override
    public Mono<Map<String, Object>> transformProtocol(Map<String, Object> request, String targetProtocol) {
        return Mono.fromCallable(() -> {
            Map<String, Object> transformed = new HashMap<>(request);
            transformed.put("protocol", targetProtocol);
            return transformed;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Boolean> authenticateRequest(Map<String, String> headers) {
        return Mono.fromCallable(() -> {
            if (headers == null) {
                return false;
            }
            String authHeader = headers.get("Authorization");
            if (authHeader == null) {
                return true;
            }
            return authHeader.startsWith("Bearer ") || authHeader.startsWith("Basic ");
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Map<String, String>> transformHeaders(Map<String, String> headers) {
        return Mono.fromCallable(() -> {
            Map<String, String> transformed = new HashMap<>();
            if (headers != null) {
                headers.forEach((key, value) -> {
                    if (!key.toLowerCase().startsWith("x-internal-")) {
                        transformed.put(key, value);
                    }
                });
            }
            transformed.put("X-Gateway-Id", "gateway-" + System.currentTimeMillis());
            transformed.put("X-Forwarded-For", "127.0.0.1");
            return transformed;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private RouteConfig findMatchingRoute(String path) {
        for (Map.Entry<String, RouteConfig> entry : routeRegistry.entrySet()) {
            String pattern = entry.getKey().replace("**", ".*");
            if (path.matches(pattern)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static class RouteConfig {
        String serviceName;
        String targetUrl;
        String protocol;

        RouteConfig(String serviceName, String targetUrl, String protocol) {
            this.serviceName = serviceName;
            this.targetUrl = targetUrl;
            this.protocol = protocol;
        }
    }
}
