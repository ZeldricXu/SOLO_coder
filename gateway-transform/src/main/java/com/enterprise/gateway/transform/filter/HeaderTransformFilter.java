package com.enterprise.gateway.transform.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

import com.enterprise.gateway.common.util.JacksonUtil;

@Slf4j
@Component
public class HeaderTransformFilter implements GlobalFilter, Ordered {

    private static final String HEADER_TRANSFORM_CONFIG = "headerTransform";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        if (route == null || route.getMetadata() == null) {
            return chain.filter(exchange);
        }

        Object configObj = route.getMetadata().get(HEADER_TRANSFORM_CONFIG);
        if (configObj == null) {
            return chain.filter(exchange);
        }

        Map<String, Object> config;
        try {
            String configStr = configObj instanceof String ? (String) configObj : JacksonUtil.toJson(configObj);
            config = JacksonUtil.toMap(configStr);
        } catch (Exception e) {
            log.error("Failed to parse header transform config", e);
            return chain.filter(exchange);
        }

        ServerHttpRequest mutatedRequest = transformRequestHeaders(exchange.getRequest(), config);
        ServerHttpResponse mutatedResponse = transformResponseHeaders(exchange.getResponse(), config);

        ServerWebExchange mutatedExchange = exchange.mutate()
                .request(mutatedRequest)
                .response(mutatedResponse)
                .build();

        return chain.filter(mutatedExchange);
    }

    private ServerHttpRequest transformRequestHeaders(ServerHttpRequest request, Map<String, Object> config) {
        HttpHeaders headers = HttpHeaders.writableHttpHeaders(request.getHeaders());

        if (config.containsKey("remove")) {
            List<String> removeHeaders = (List<String>) config.get("remove");
            removeHeaders.forEach(headers::remove);
        }

        if (config.containsKey("add")) {
            Map<String, String> addHeaders = (Map<String, String>) config.get("add");
            addHeaders.forEach(headers::add);
        }

        if (config.containsKey("modify")) {
            Map<String, String> modifyHeaders = (Map<String, String>) config.get("modify");
            modifyHeaders.forEach(headers::set);
        }

        return new ServerHttpRequestDecorator(request) {
            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }
        };
    }

    private ServerHttpResponse transformResponseHeaders(ServerHttpResponse response, Map<String, Object> config) {
        return new ServerHttpResponseDecorator(response) {
            @Override
            public HttpHeaders getHeaders() {
                HttpHeaders headers = HttpHeaders.writableHttpHeaders(super.getHeaders());

                if (config.containsKey("remove")) {
                    List<String> removeHeaders = (List<String>) config.get("remove");
                    removeHeaders.forEach(headers::remove);
                }

                if (config.containsKey("add")) {
                    Map<String, String> addHeaders = (Map<String, String>) config.get("add");
                    addHeaders.forEach(headers::add);
                }

                if (config.containsKey("modify")) {
                    Map<String, String> modifyHeaders = (Map<String, String>) config.get("modify");
                    modifyHeaders.forEach(headers::set);
                }

                return headers;
            }
        };
    }

    @Override
    public int getOrder() {
        return -30;
    }
}
