package com.enterprise.gateway.transform.spi;

import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import reactor.core.publisher.Mono;

public interface GrpcToRestTranscoder {

    default boolean supports(String serviceName, String methodName) {
        return false;
    }

    default Mono<ServerHttpResponse> transcode(ServerHttpRequest request, String serviceName, String methodName) {
        return Mono.empty();
    }
}
