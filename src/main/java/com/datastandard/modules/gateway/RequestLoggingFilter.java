package com.datastandard.modules.gateway;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static com.datastandard.modules.gateway.TraceContextFilter.REQUEST_ID_ATTR;
import static com.datastandard.modules.gateway.TraceContextFilter.TRACE_ID_ATTR;
import static com.datastandard.modules.gateway.TraceContextFilter.SPAN_ID_ATTR;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
@RequiredArgsConstructor
public class RequestLoggingFilter implements WebFilter {

    private static final int MAX_LOG_BODY_LENGTH = 4096;

    private final GatewayAccessLogService accessLogService;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        long startTime = System.nanoTime();

        String requestId = (String) exchange.getAttribute(REQUEST_ID_ATTR);
        String traceId = (String) exchange.getAttribute(TRACE_ID_ATTR);
        String spanId = (String) exchange.getAttribute(SPAN_ID_ATTR);

        return captureRequestBody(exchange, chain, startTime, requestId, traceId, spanId);
    }

    private Mono<Void> captureRequestBody(ServerWebExchange exchange, WebFilterChain chain,
                                          long startTime, String requestId, String traceId, String spanId) {
        ServerHttpRequest request = exchange.getRequest();

        return DataBufferUtils.join(request.getBody())
                .map(buffer -> {
                    String body = bufferToString(buffer);
                    DataBufferFactory bufferFactory = exchange.getResponse().bufferFactory();
                    DataBuffer newBuffer = bufferFactory.wrap(body.getBytes(StandardCharsets.UTF_8));

                    ServerHttpRequest mutatedRequest = new ServerHttpRequestDecorator(request) {
                        @Override
                        public Flux<DataBuffer> getBody() {
                            return Flux.just(newBuffer);
                        }
                    };

                    return new Object[]{mutatedRequest, body};
                })
                .defaultIfEmpty(new Object[]{request, ""})
                .flatMap(args -> {
                    ServerHttpRequest mutatedRequest = (ServerHttpRequest) args[0];
                    String requestBody = (String) args[1];
                    ServerWebExchange mutatedExchange = exchange.mutate().request(mutatedRequest).build();

                    return captureResponseBody(mutatedExchange, chain, startTime,
                            requestId, traceId, spanId, requestBody);
                });
    }

    private Mono<Void> captureResponseBody(ServerWebExchange exchange, WebFilterChain chain,
                                           long startTime, String requestId, String traceId, String spanId,
                                           String requestBody) {
        ServerHttpResponse originalResponse = exchange.getResponse();
        DataBufferFactory bufferFactory = originalResponse.bufferFactory();

        ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(originalResponse) {
            @Override
            public Mono<Void> writeWith(org.reactivestreams.Publisher<? extends DataBuffer> body) {
                if (body instanceof Flux) {
                    Flux<? extends DataBuffer> fluxBody = Flux.from(body);
                    return super.writeWith(fluxBody.collectList().map(buffers -> {
                        StringBuilder responseBody = new StringBuilder();
                        for (DataBuffer buffer : buffers) {
                            responseBody.append(bufferToString(buffer));
                        }
                        String responseStr = responseBody.toString();

                        long durationMs = Duration.ofNanos(System.nanoTime() - startTime).toMillis();
                        String errorMessage = extractErrorMessage(getStatusCode());

                        accessLogService.recordAccess(
                                exchange.getRequest(),
                                exchange.getResponse(),
                                requestId, traceId, spanId,
                                durationMs, errorMessage,
                                requestBody, responseStr
                        );

                        logRequest(exchange.getRequest(), exchange.getResponse(), durationMs,
                                requestId, traceId, errorMessage);

                        byte[] responseBytes = responseStr.getBytes(StandardCharsets.UTF_8);
                        DataBuffer buffer = bufferFactory.wrap(responseBytes);
                        getHeaders().setContentLength(responseBytes.length);
                        return buffer;
                    }));
                }
                return super.writeWith(body);
            }
        };

        return chain.filter(exchange.mutate().response(decoratedResponse).build())
                .doOnError(e -> {
                    long durationMs = Duration.ofNanos(System.nanoTime() - startTime).toMillis();
                    accessLogService.recordAccess(
                            exchange.getRequest(),
                            exchange.getResponse(),
                            requestId, traceId, spanId,
                            durationMs, e.getMessage(),
                            requestBody, ""
                    );
                    log.error("Request error: requestId={}, error={}", requestId, e.getMessage(), e);
                });
    }

    private String bufferToString(DataBuffer buffer) {
        byte[] bytes = new byte[buffer.readableByteCount()];
        buffer.read(bytes);
        DataBufferUtils.release(buffer);
        String body = new String(bytes, StandardCharsets.UTF_8);
        if (body.length() > MAX_LOG_BODY_LENGTH) {
            return body.substring(0, MAX_LOG_BODY_LENGTH) + "... [truncated]";
        }
        return body;
    }

    private String extractErrorMessage(HttpStatus status) {
        if (status == null) {
            return null;
        }
        if (status.isError()) {
            return status.getReasonPhrase();
        }
        return null;
    }

    private void logRequest(ServerHttpRequest request, ServerHttpResponse response,
                            long durationMs, String requestId, String traceId, String errorMessage) {
        int status = response.getStatusCode() != null ? response.getStatusCode().value() : 0;
        String method = request.getMethod().name();
        String path = request.getPath().value();

        if (status >= 400) {
            log.warn("{} {} {} - {}ms [requestId={}, traceId={}] - {}",
                    method, path, status, durationMs, requestId, traceId, errorMessage);
        } else {
            log.info("{} {} {} - {}ms [requestId={}, traceId={}]",
                    method, path, status, durationMs, requestId, traceId);
        }
    }
}
