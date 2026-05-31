package com.modelguard.handler;

import com.modelguard.config.MetricsConfig;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.time.Instant;

@Component
public class MetricsInterceptor implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        Instant start = Instant.now();
        MetricsConfig.requestCounter.increment();
        MetricsConfig.activeRequests.incrementAndGet();

        return chain.filter(exchange)
                .doFinally(signalType -> {
                    MetricsConfig.activeRequests.decrementAndGet();
                    MetricsConfig.requestTimer.record(Duration.between(start, Instant.now()));
                    if (exchange.getResponse().getStatusCode() != null &&
                            exchange.getResponse().getStatusCode().isError()) {
                        MetricsConfig.errorCounter.increment();
                    }
                });
    }
}
