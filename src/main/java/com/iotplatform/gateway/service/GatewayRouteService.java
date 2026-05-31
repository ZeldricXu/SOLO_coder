package com.iotplatform.gateway.service;

import com.iotplatform.gateway.dto.RouteDefinition;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface GatewayRouteService {

    Mono<RouteDefinition> addRoute(RouteDefinition route);

    Mono<Void> removeRoute(String routeId);

    Mono<RouteDefinition> updateRoute(RouteDefinition route);

    Mono<RouteDefinition> getRoute(String routeId);

    Flux<RouteDefinition> getAllRoutes();

    Mono<Void> refreshRoutes();

    Mono<Void> enableRoute(String routeId);

    Mono<Void> disableRoute(String routeId);
}
