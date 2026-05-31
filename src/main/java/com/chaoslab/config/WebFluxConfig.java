package com.chaoslab.config;

import com.chaoslab.common.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Slf4j
@Configuration
public class WebFluxConfig {

    @Bean
    public WebFilter traceIdFilter() {
        return (ServerWebExchange exchange, WebFilterChain chain) -> {
            ServerHttpRequest request = exchange.getRequest();
            String existingTraceId = request.getHeaders().getFirst("X-Trace-Id");
            String traceId = existingTraceId != null && !existingTraceId.isEmpty()
                    ? existingTraceId
                    : TraceContext.generateTraceId();

            log.debug("Processing request: {} {} traceId: {}",
                    request.getMethod(), request.getPath(), traceId);

            return chain.filter(exchange)
                    .contextWrite(ctx -> ctx.put(TraceContext.TRACE_ID_KEY, traceId))
                    .doOnSuccess(aVoid -> {
                        exchange.getResponse().getHeaders().set("X-Trace-Id", traceId);
                    });
        };
    }

    @Bean
    public WebFilter requestLoggingFilter() {
        return (ServerWebExchange exchange, WebFilterChain chain) -> {
            long startTime = System.currentTimeMillis();
            ServerHttpRequest request = exchange.getRequest();

            return chain.filter(exchange)
                    .doOnSuccess(aVoid -> {
                        long duration = System.currentTimeMillis() - startTime;
                        int status = exchange.getResponse().getStatusCode() != null
                                ? exchange.getResponse().getStatusCode().value()
                                : 200;
                        log.info("Request completed: {} {} status: {} duration: {}ms",
                                request.getMethod(), request.getPath(), status, duration);
                    })
                    .doOnError(error -> {
                        long duration = System.currentTimeMillis() - startTime;
                        log.error("Request failed: {} {} duration: {}ms error: {}",
                                request.getMethod(), request.getPath(), duration, error.getMessage());
                    });
        };
    }
}
