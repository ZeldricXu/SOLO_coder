package com.edgescheduler.common.security;

import com.edgescheduler.common.exception.ValidationException;
import com.edgescheduler.common.util.SignatureUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class AuthInterceptor implements WebFilter {

    private static final int TIMESTAMP_EXPIRE_SECONDS = 300;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        
        if (path.startsWith("/actuator") || path.startsWith("/api/v1/auth")) {
            return chain.filter(exchange);
        }

        return exchange.getRequest().getBody()
                .collectList()
                .flatMap(dataBuffers -> {
                    try {
                        Map<String, Object> params = extractParams(exchange);
                        
                        String signature = exchange.getRequest().getHeaders().getFirst("X-Signature");
                        if (signature == null || signature.isEmpty()) {
                            return Mono.error(new ValidationException("签名缺失"));
                        }

                        String timestampStr = exchange.getRequest().getHeaders().getFirst("X-Timestamp");
                        if (timestampStr == null || timestampStr.isEmpty()) {
                            return Mono.error(new ValidationException("时间戳缺失"));
                        }

                        long timestamp = Long.parseLong(timestampStr);
                        if (!SignatureUtil.validateTimestamp(timestamp, TIMESTAMP_EXPIRE_SECONDS)) {
                            return Mono.error(new ValidationException("请求已过期"));
                        }

                        if (!SignatureUtil.validateSignature(params, signature)) {
                            return Mono.error(new ValidationException("签名验证失败"));
                        }

                        return chain.filter(exchange);
                    } catch (NumberFormatException e) {
                        return Mono.error(new ValidationException("时间戳格式无效"));
                    }
                });
    }

    private Map<String, Object> extractParams(ServerWebExchange exchange) {
        Map<String, Object> params = new HashMap<>();
        exchange.getRequest().getQueryParams().forEach((key, values) -> {
            if (!values.isEmpty()) {
                params.put(key, values.get(0));
            }
        });
        params.put("timestamp", exchange.getRequest().getHeaders().getFirst("X-Timestamp"));
        return params;
    }
}
