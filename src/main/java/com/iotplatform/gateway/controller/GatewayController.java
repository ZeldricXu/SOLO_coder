package com.iotplatform.gateway.controller;

import com.iotplatform.common.dto.Result;
import com.iotplatform.gateway.dto.ProtocolConvertRequest;
import com.iotplatform.gateway.dto.RouteDefinition;
import com.iotplatform.gateway.service.GatewayRouteService;
import com.iotplatform.gateway.service.ProtocolConversionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.List;

@RestController
@RequestMapping("/api/v1/gateway")
@RequiredArgsConstructor
public class GatewayController {

    private final GatewayRouteService routeService;
    private final ProtocolConversionService conversionService;

    @PostMapping("/routes")
    public Mono<Result<RouteDefinition>> addRoute(@Valid @RequestBody RouteDefinition route) {
        return routeService.addRoute(route)
                .map(Result::success);
    }

    @DeleteMapping("/routes/{routeId}")
    public Mono<Result<Void>> removeRoute(@PathVariable String routeId) {
        return routeService.removeRoute(routeId)
                .then(Mono.just(Result.success(null)));
    }

    @PutMapping("/routes")
    public Mono<Result<RouteDefinition>> updateRoute(@Valid @RequestBody RouteDefinition route) {
        return routeService.updateRoute(route)
                .map(Result::success);
    }

    @GetMapping("/routes/{routeId}")
    public Mono<Result<RouteDefinition>> getRoute(@PathVariable String routeId) {
        return routeService.getRoute(routeId)
                .map(Result::success);
    }

    @GetMapping("/routes")
    public Mono<Result<List<RouteDefinition>>> getAllRoutes() {
        return routeService.getAllRoutes()
                .collectList()
                .map(Result::success);
    }

    @PostMapping("/routes/refresh")
    public Mono<Result<Void>> refreshRoutes() {
        return routeService.refreshRoutes()
                .then(Mono.just(Result.success(null)));
    }

    @PostMapping("/routes/{routeId}/enable")
    public Mono<Result<Void>> enableRoute(@PathVariable String routeId) {
        return routeService.enableRoute(routeId)
                .then(Mono.just(Result.success(null)));
    }

    @PostMapping("/routes/{routeId}/disable")
    public Mono<Result<Void>> disableRoute(@PathVariable String routeId) {
        return routeService.disableRoute(routeId)
                .then(Mono.just(Result.success(null)));
    }

    @PostMapping("/protocol/convert")
    public Mono<Result<String>> convertProtocol(@Valid @RequestBody ProtocolConvertRequest request) {
        return conversionService.convert(request)
                .map(Result::success);
    }

    @GetMapping("/protocol/supported")
    public Mono<Result<List<String>>> getSupportedProtocols() {
        return conversionService.getSupportedProtocols()
                .collectList()
                .map(Result::success);
    }

    @GetMapping("/protocol/supports/{protocol}")
    public Mono<Result<Boolean>> supportsProtocol(@PathVariable String protocol) {
        return conversionService.supportsProtocol(protocol)
                .map(Result::success);
    }
}
