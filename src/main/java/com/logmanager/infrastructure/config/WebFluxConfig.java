package com.logmanager.infrastructure.config;

import com.logmanager.common.utils.TraceIdContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

@Configuration
public class WebFluxConfig implements WebFluxConfigurer {

    @Bean
    public WebFilter traceIdFilter() {
        return (exchange, chain) -> {
            String traceId = exchange.getRequest().getHeaders().getFirst("X-Trace-Id");
            if (traceId == null || traceId.isEmpty()) {
                traceId = TraceIdContext.generateTraceId();
            }
            exchange.getResponse().getHeaders().set("X-Trace-Id", traceId);
            return chain.filter(exchange)
                    .contextWrite(TraceIdContext.withTraceId(traceId));
        };
    }

    @Bean
    public WebFilter requestLoggingFilter() {
        return (exchange, chain) -> {
            long startTime = System.currentTimeMillis();
            String path = exchange.getRequest().getPath().value();
            String method = exchange.getRequest().getMethod().name();

            return chain.filter(exchange)
                    .then(Mono.fromRunnable(() -> {
                        long duration = System.currentTimeMillis() - startTime;
                        int status = exchange.getResponse().getStatusCode() != null
                                ? exchange.getResponse().getStatusCode().value()
                                : 200;
                        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger("HTTP_ACCESS");
                        log.info("{} {} {} - {}ms", method, path, status, duration);
                    }));
        };
    }
}
