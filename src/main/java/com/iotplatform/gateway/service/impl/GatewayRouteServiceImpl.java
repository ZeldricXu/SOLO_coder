package com.iotplatform.gateway.service.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.iotplatform.common.constant.CacheConstants;
import com.iotplatform.common.constant.ErrorCodeConstants;
import com.iotplatform.common.exception.BusinessException;
import com.iotplatform.common.util.CacheKeyUtil;
import com.iotplatform.gateway.dto.RouteDefinition;
import com.iotplatform.gateway.service.GatewayRouteService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class GatewayRouteServiceImpl implements GatewayRouteService {

    private final MeterRegistry meterRegistry;

    private final Map<String, RouteDefinition> routeStore = new ConcurrentHashMap<>();

    private final Cache<String, RouteDefinition> routeCache = Caffeine.newBuilder()
            .maximumSize(CacheConstants.ROUTE_CACHE_MAX_SIZE)
            .expireAfterWrite(Duration.ofSeconds(CacheConstants.ROUTE_CACHE_SECONDS))
            .recordStats()
            .build();

    @Override
    public Mono<RouteDefinition> addRoute(RouteDefinition route) {
        return Mono.fromCallable(() -> {
            String routeId = route.getRouteId();
            if (routeStore.containsKey(routeId)) {
                throw new BusinessException(ErrorCodeConstants.ROUTE_ID_EXISTS, "路由ID已存在: " + routeId);
            }

            route.setCreatedAt(LocalDateTime.now());
            route.setUpdatedAt(LocalDateTime.now());
            routeStore.put(routeId, route);

            String cacheKey = CacheKeyUtil.routeKey(routeId);
            routeCache.put(cacheKey, route);

            log.info("Route added: {}", routeId);
            return route;
        });
    }

    @Override
    public Mono<Void> removeRoute(String routeId) {
        return Mono.fromCallable(() -> {
            RouteDefinition removed = routeStore.remove(routeId);
            if (removed == null) {
                throw new BusinessException(ErrorCodeConstants.ROUTE_NOT_FOUND, "路由不存在: " + routeId);
            }

            String cacheKey = CacheKeyUtil.routeKey(routeId);
            routeCache.invalidate(cacheKey);

            log.info("Route removed: {}", routeId);
            return null;
        });
    }

    @Override
    public Mono<RouteDefinition> updateRoute(RouteDefinition route) {
        return Mono.fromCallable(() -> {
            String routeId = route.getRouteId();
            if (!routeStore.containsKey(routeId)) {
                throw new BusinessException(ErrorCodeConstants.ROUTE_NOT_FOUND, "路由不存在: " + routeId);
            }

            RouteDefinition existing = routeStore.get(routeId);
            route.setCreatedAt(existing.getCreatedAt());
            route.setUpdatedAt(LocalDateTime.now());
            route.setCreatedBy(existing.getCreatedBy());
            routeStore.put(routeId, route);

            String cacheKey = CacheKeyUtil.routeKey(routeId);
            routeCache.put(cacheKey, route);

            log.info("Route updated: {}", routeId);
            return route;
        });
    }

    @Override
    public Mono<RouteDefinition> getRoute(String routeId) {
        return Mono.fromCallable(() -> {
            String cacheKey = CacheKeyUtil.routeKey(routeId);
            RouteDefinition cached = routeCache.getIfPresent(cacheKey);
            if (cached != null) {
                return cached;
            }

            RouteDefinition route = routeStore.get(routeId);
            if (route == null) {
                throw new BusinessException(ErrorCodeConstants.ROUTE_NOT_FOUND, "路由不存在: " + routeId);
            }

            routeCache.put(cacheKey, route);
            return route;
        });
    }

    @Override
    public Flux<RouteDefinition> getAllRoutes() {
        return Flux.fromIterable(new ArrayList<>(routeStore.values()));
    }

    @Override
    public Mono<Void> refreshRoutes() {
        return Mono.fromRunnable(() -> {
            routeCache.invalidateAll();
            log.info("Routes cache refreshed, total routes: {}", routeStore.size());
        });
    }

    @Override
    public Mono<Void> enableRoute(String routeId) {
        return updateRouteStatus(routeId, true);
    }

    @Override
    public Mono<Void> disableRoute(String routeId) {
        return updateRouteStatus(routeId, false);
    }

    private Mono<Void> updateRouteStatus(String routeId, boolean enabled) {
        return Mono.fromCallable(() -> {
            RouteDefinition route = routeStore.get(routeId);
            if (route == null) {
                throw new BusinessException(ErrorCodeConstants.ROUTE_NOT_FOUND, "路由不存在: " + routeId);
            }

            route.setEnabled(enabled);
            route.setUpdatedAt(LocalDateTime.now());
            routeStore.put(routeId, route);

            String cacheKey = CacheKeyUtil.routeKey(routeId);
            routeCache.invalidate(cacheKey);

            log.info("Route {}: {}", enabled ? "enabled" : "disabled", routeId);
            return null;
        });
    }

    public List<RouteDefinition> getEnabledRoutes() {
        return routeStore.values().stream()
                .filter(route -> Boolean.TRUE.equals(route.getEnabled()))
                .toList();
    }
}
