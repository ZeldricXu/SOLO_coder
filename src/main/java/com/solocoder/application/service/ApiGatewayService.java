package com.solocoder.application.service;

import com.solocoder.domain.model.ApiResponse;
import com.solocoder.domain.port.ApiGatewayPort;
import com.solocoder.domain.port.StructuredLoggerPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ApiGatewayService {

    private final ApiGatewayPort apiGatewayPort;
    private final StructuredLoggerPort logger;

    public Mono<ApiResponse<Map<String, Object>>> routeRequest(String path, String method,
                                                                Map<String, String> headers,
                                                                Map<String, Object> body) {
        Map<String, Object> context = Map.of(
                "traceId", UUID.randomUUID().toString(),
                "path", path,
                "method", method
        );
        logger.info("路由请求", context);

        return apiGatewayPort.authenticateRequest(headers)
                .flatMap(authenticated -> {
                    if (!authenticated) {
                        logger.warn("认证失败", context);
                        return Mono.just(ApiResponse.error(401, "未授权访问"));
                    }

                    return apiGatewayPort.transformHeaders(headers)
                            .flatMap(transformedHeaders ->
                                    apiGatewayPort.routeRequest(path, method, transformedHeaders, body))
                            .map(ApiResponse::success)
                            .onErrorResume(e -> {
                                logger.error("路由失败", e, context);
                                return Mono.just(ApiResponse.error(500, "路由失败: " + e.getMessage()));
                            });
                });
    }

    public Mono<ApiResponse<Void>> registerRoute(String path, String targetService, String protocol) {
        return apiGatewayPort.registerRoute(path, targetService, protocol)
                .then(Mono.just(ApiResponse.success(null)))
                .onErrorResume(e -> Mono.just(ApiResponse.error(500, e.getMessage())));
    }

    public Mono<ApiResponse<Void>> removeRoute(String path) {
        return apiGatewayPort.removeRoute(path)
                .then(Mono.just(ApiResponse.success(null)))
                .onErrorResume(e -> Mono.just(ApiResponse.error(500, e.getMessage())));
    }

    public Mono<ApiResponse<Map<String, Object>>> getRouteConfig(String path) {
        Map<String, Object> config = apiGatewayPort.getRouteConfig(path);
        if (config == null) {
            return Mono.just(ApiResponse.error(404, "路由不存在"));
        }
        return Mono.just(ApiResponse.success(config));
    }
}
