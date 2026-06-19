package com.enterprise.gateway.auth.filter;

import com.enterprise.gateway.common.model.UnifiedResponse;
import com.enterprise.gateway.common.util.JacksonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class IpFilter implements GlobalFilter, Ordered {

    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String IP_WHITELIST_KEY = "ip:whitelist";
    private static final String IP_BLACKLIST_KEY = "ip:blacklist";
    private static final String ROUTE_IP_PREFIX = "ip:route:";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String clientIp = getClientIp(exchange.getRequest());
        if (clientIp == null) {
            return sendForbiddenError(exchange, "Unable to determine client IP");
        }

        String routeId = getRouteId(exchange);

        return checkBlacklist(clientIp, routeId)
                .flatMap(isBlacklisted -> {
                    if (isBlacklisted) {
                        return sendForbiddenError(exchange, "IP is blacklisted: " + clientIp);
                    }
                    return checkWhitelist(clientIp, routeId);
                })
                .flatMap(isWhitelisted -> {
                    if (!isWhitelisted) {
                        return sendForbiddenError(exchange, "IP is not in whitelist: " + clientIp);
                    }
                    return chain.filter(exchange);
                });
    }

    @Override
    public int getOrder() {
        return -150;
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

        InetSocketAddress remoteAddress = request.getRemoteAddress();
        if (remoteAddress != null && remoteAddress.getAddress() != null) {
            return remoteAddress.getAddress().getHostAddress();
        }

        return null;
    }

    private String getRouteId(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        return route != null ? route.getId() : null;
    }

    private Mono<Boolean> checkBlacklist(String clientIp, String routeId) {
        return getIpRules(IP_BLACKLIST_KEY, routeId, "blacklist")
                .map(blacklist -> matchesIpRules(clientIp, blacklist));
    }

    private Mono<Boolean> checkWhitelist(String clientIp, String routeId) {
        return getIpRules(IP_WHITELIST_KEY, routeId, "whitelist")
                .flatMap(globalWhitelist -> {
                    if (!globalWhitelist.isEmpty()) {
                        return Mono.just(matchesIpRules(clientIp, globalWhitelist));
                    }
                    return Mono.just(true);
                });
    }

    private Mono<List<String>> getIpRules(String globalKey, String routeId, String type) {
        Mono<List<String>> globalRules = redisTemplate.opsForValue().get(globalKey)
                .map(this::parseIpRules)
                .defaultIfEmpty(Collections.emptyList());

        if (routeId != null) {
            String routeKey = ROUTE_IP_PREFIX + routeId + ":" + type;
            return redisTemplate.opsForValue().get(routeKey)
                    .map(this::parseIpRules)
                    .defaultIfEmpty(Collections.emptyList())
                    .flatMap(routeRules -> globalRules.map(global -> {
                        if (!routeRules.isEmpty()) {
                            return routeRules;
                        }
                        return global;
                    }));
        }

        return globalRules;
    }

    private List<String> parseIpRules(String json) {
        try {
            Map<String, Object> data = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            if (data.containsKey("ips")) {
                return objectMapper.convertValue(data.get("ips"), new TypeReference<List<String>>() {});
            }
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.error("Failed to parse IP rules from JSON: {}", json, e);
            return Collections.emptyList();
        }
    }

    private boolean matchesIpRules(String clientIp, List<String> ipRules) {
        for (String rule : ipRules) {
            if (matchesIpRule(clientIp, rule)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesIpRule(String clientIp, String rule) {
        if (rule.contains("/")) {
            return matchesCidr(clientIp, rule);
        }
        return clientIp.equals(rule);
    }

    private boolean matchesCidr(String clientIp, String cidr) {
        try {
            String[] parts = cidr.split("/");
            String networkAddress = parts[0];
            int prefixLength = Integer.parseInt(parts[1]);

            byte[] clientBytes = InetAddress.getByName(clientIp).getAddress();
            byte[] networkBytes = InetAddress.getByName(networkAddress).getAddress();

            if (clientBytes.length != networkBytes.length) {
                return false;
            }

            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;

            for (int i = 0; i < fullBytes; i++) {
                if (clientBytes[i] != networkBytes[i]) {
                    return false;
                }
            }

            if (remainingBits > 0 && fullBytes < clientBytes.length) {
                int mask = 0xFF << (8 - remainingBits);
                if ((clientBytes[fullBytes] & mask) != (networkBytes[fullBytes] & mask)) {
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            log.error("Failed to check CIDR match: {} against {}", clientIp, cidr, e);
            return false;
        }
    }

    private Mono<Void> sendForbiddenError(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        UnifiedResponse<?> errorResponse = UnifiedResponse.error(403, message);
        String json = JacksonUtil.toJson(errorResponse);
        DataBuffer buffer = response.bufferFactory().wrap(json.getBytes(StandardCharsets.UTF_8));

        return response.writeWith(Mono.just(buffer));
    }
}
