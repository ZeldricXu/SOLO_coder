package com.device.platform.gateway;

import com.device.platform.common.JsonUtils;
import com.device.platform.common.TraceContext;
import com.device.platform.entity.RequestLog;
import com.device.platform.mapper.RequestLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class RequestLoggingFilter implements WebFilter {

    private final RequestLogMapper requestLogMapper;

    @Value("${gateway.log.request-body:true}")
    private boolean logRequestBody;

    @Value("${gateway.log.response-body:false}")
    private boolean logResponseBody;

    @Value("${gateway.max-body-size:10240}")
    private int maxBodySize;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        Instant startTime = Instant.now();

        String traceId = request.getHeaders().getFirst("X-Trace-Id");
        if (traceId == null || traceId.isEmpty()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        String spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String parentSpanId = request.getHeaders().getFirst("X-Span-Id");

        TraceContext ctx = new TraceContext(traceId);
        exchange.getAttributes().put("traceContext", ctx);
        exchange.getAttributes().put("traceId", traceId);
        exchange.getAttributes().put("spanId", spanId);

        ServerHttpRequest mutatedRequest = request.mutate()
                .header("X-Trace-Id", traceId)
                .header("X-Span-Id", spanId)
                .build();

        ServerWebExchange mutatedExchange = exchange.mutate()
                .request(mutatedRequest)
                .build();

        RequestLog requestLog = new RequestLog();
        requestLog.setTraceId(traceId);
        requestLog.setSpanId(spanId);
        requestLog.setParentSpanId(parentSpanId);
        requestLog.setServiceName("device-platform");
        requestLog.setRequestMethod(request.getMethod().name());
        requestLog.setRequestPath(request.getPath().value());
        requestLog.setClientIp(getClientIp(request));
        requestLog.setUserAgent(request.getHeaders().getFirst(HttpHeaders.USER_AGENT));
        requestLog.setStartTime(startTime);

        if (logRequestBody) {
            requestLog.setRequestHeaders(JsonUtils.toJson(getHeadersMap(request.getHeaders())));
            return logRequestBody(mutatedExchange, chain, requestLog);
        }

        return chain.filter(mutatedExchange)
                .then(Mono.defer(() -> logResponse(exchange, requestLog, startTime)));
    }

    private Mono<Void> logRequestBody(ServerWebExchange exchange, WebFilterChain chain, RequestLog requestLog) {
        ServerHttpRequest request = exchange.getRequest();

        return DataBufferUtils.join(request.getBody())
                .flatMap(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);

                    String body = new String(bytes, StandardCharsets.UTF_8);
                    if (body.length() > maxBodySize) {
                        body = body.substring(0, maxBodySize) + "... [truncated]";
                    }
                    requestLog.setRequestBody(body);

                    Flux<DataBuffer> cachedBody = Flux.defer(() -> {
                        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
                        return Flux.just(buffer);
                    });

                    ServerHttpRequest mutatedRequest = request.mutate().body(cachedBody).build();
                    ServerWebExchange mutatedExchange = exchange.mutate().request(mutatedRequest).build();

                    return chain.filter(mutatedExchange)
                            .then(Mono.defer(() -> logResponse(exchange, requestLog, requestLog.getStartTime())));
                })
                .switchIfEmpty(chain.filter(exchange)
                        .then(Mono.defer(() -> logResponse(exchange, requestLog, requestLog.getStartTime()))));
    }

    private Mono<Void> logResponse(ServerWebExchange exchange, RequestLog requestLog, Instant startTime) {
        ServerHttpResponse response = exchange.getResponse();
        Instant endTime = Instant.now();

        requestLog.setResponseStatus(response.getStatusCode() != null ? response.getStatusCode().value() : 0);
        requestLog.setDurationMs(java.time.Duration.between(startTime, endTime).toMillis());
        requestLog.setEndTime(endTime);

        if (response.getStatusCode() != null && response.getStatusCode().isError()) {
            requestLog.setErrorMessage("HTTP " + response.getStatusCode().value());
        }

        if (logResponseBody) {
            return logResponseBody(exchange, requestLog);
        }

        return saveRequestLog(requestLog)
                .doOnError(e -> log.error("保存请求日志失败: traceId={}", requestLog.getTraceId(), e))
                .then();
    }

    private Mono<Void> logResponseBody(ServerWebExchange exchange, RequestLog requestLog) {
        ServerHttpResponse response = exchange.getResponse();

        ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(response) {
            @Override
            public Mono<Void> writeWith(org.reactivestreams.Publisher<? extends DataBuffer> body) {
                if (body instanceof Flux) {
                    Flux<? extends DataBuffer> fluxBody = (Flux<? extends DataBuffer>) body;
                    return super.writeWith(fluxBody.buffer().map(dataBuffers -> {
                        DataBuffer joinedBuffer = dataBufferFactory().join(dataBuffers);
                        byte[] bytes = new byte[joinedBuffer.readableByteCount()];
                        joinedBuffer.read(bytes);
                        DataBufferUtils.release(joinedBuffer);

                        String responseBody = new String(bytes, StandardCharsets.UTF_8);
                        if (responseBody.length() > maxBodySize) {
                            responseBody = responseBody.substring(0, maxBodySize) + "... [truncated]";
                        }
                        requestLog.setResponseBody(responseBody);

                        saveRequestLog(requestLog)
                                .doOnError(e -> log.error("保存请求日志失败: traceId={}", requestLog.getTraceId(), e))
                                .subscribe();

                        return bufferFactory().wrap(bytes);
                    }));
                }
                return super.writeWith(body);
            }
        };

        return Mono.empty();
    }

    private Mono<Void> saveRequestLog(RequestLog requestLog) {
        return Mono.fromCallable(() -> {
            requestLogMapper.insert(requestLog);
            log.debug("请求日志已保存: traceId={}, path={}, status={}, duration={}ms",
                    requestLog.getTraceId(), requestLog.getRequestPath(),
                    requestLog.getResponseStatus(), requestLog.getDurationMs());
            return null;
        });
    }

    private String getClientIp(ServerHttpRequest request) {
        String xForwardedFor = request.getHeaders().getFirst("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = request.getHeaders().getFirst("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }
        return request.getRemoteAddress() != null ?
                request.getRemoteAddress().getAddress().getHostAddress() : "unknown";
    }

    private Map<String, String> getHeadersMap(HttpHeaders headers) {
        Map<String, String> headersMap = new HashMap<>();
        headers.forEach((key, values) -> {
            if (!"Authorization".equalsIgnoreCase(key) && !"Cookie".equalsIgnoreCase(key)) {
                headersMap.put(key, String.join(", ", values));
            }
        });
        return headersMap;
    }
}
