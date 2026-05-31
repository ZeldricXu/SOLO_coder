package com.nftindexer.config;

import com.nftindexer.common.TraceContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.config.CorsRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

@Configuration
public class WebFluxConfig implements WebFluxConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Bean
    public WebFilter traceIdFilter() {
        return (exchange, chain) -> {
            String traceId = exchange.getRequest().getHeaders()
                    .getFirst("X-Trace-Id");
            if (traceId == null || traceId.isEmpty()) {
                traceId = TraceContext.generateTraceId();
            }

            String finalTraceId = traceId;
            exchange.getResponse().getHeaders().add("X-Trace-Id", finalTraceId);

            return chain.filter(exchange)
                    .contextWrite(TraceContext.withTraceId(finalTraceId));
        };
    }

    @Bean
    public WebFilter metricsFilter() {
        return (exchange, chain) -> {
            long startTime = System.currentTimeMillis();
            String path = exchange.getRequest().getPath().value();
            String method = exchange.getRequest().getMethod().name();

            return chain.filter(exchange)
                    .doOnSuccess(v -> {
                        long duration = System.currentTimeMillis() - startTime;
                        int status = exchange.getResponse().getStatusCode() != null ?
                                exchange.getResponse().getStatusCode().value() : 200;
                        logRequest(method, path, status, duration, null);
                    })
                    .doOnError(e -> {
                        long duration = System.currentTimeMillis() - startTime;
                        logRequest(method, path, 500, duration, e.getMessage());
                    })
                    .onErrorResume(e -> Mono.error(e));
        };
    }

    @Bean
    public WebClient.Builder webClientBuilder() {
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(java.time.Duration.ofSeconds(30));

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader("Content-Type", "application/json");
    }

    @Bean
    public WebClient webClient(WebClient.Builder builder) {
        return builder.build();
    }

    private void logRequest(String method, String path, int status, long duration, String error) {
        if (error != null) {
            org.slf4j.LoggerFactory.getLogger("HTTP")
                    .warn("{} {} {} {}ms - {}", method, path, status, duration, error);
        } else if (status >= 400) {
            org.slf4j.LoggerFactory.getLogger("HTTP")
                    .info("{} {} {} {}ms", method, path, status, duration);
        } else {
            org.slf4j.LoggerFactory.getLogger("HTTP")
                    .debug("{} {} {} {}ms", method, path, status, duration);
        }
    }
}
