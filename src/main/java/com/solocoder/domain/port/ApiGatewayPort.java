package com.solocoder.domain.port;

import reactor.core.publisher.Mono;

import java.util.Map;

public interface ApiGatewayPort {

    Mono<Map<String, Object>> routeRequest(String path, String method,
                                           Map<String, String> headers,
                                           Map<String, Object> body);

    Mono<Void> registerRoute(String path, String targetService, String protocol);

    Mono<Void> removeRoute(String path);

    Map<String, Object> getRouteConfig(String path);

    Mono<Map<String, Object>> transformProtocol(Map<String, Object> request, String targetProtocol);

    Mono<Boolean> authenticateRequest(Map<String, String> headers);

    Mono<Map<String, String>> transformHeaders(Map<String, String> headers);
}
