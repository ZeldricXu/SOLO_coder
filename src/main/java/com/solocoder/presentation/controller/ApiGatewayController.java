package com.solocoder.presentation.controller;

import com.solocoder.application.service.ApiGatewayService;
import com.solocoder.domain.model.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/gateway")
@RequiredArgsConstructor
public class ApiGatewayController {

    private final ApiGatewayService apiGatewayService;

    @PostMapping("/route")
    public Mono<ApiResponse<Map<String, Object>>> routeRequest(
            @RequestBody @Valid Map<String, Object> request,
            @RequestHeader HttpHeaders headers) {

        String path = (String) request.get("path");
        String method = (String) request.getOrDefault("method", "GET");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) request.get("body");

        Map<String, String> headerMap = new java.util.HashMap<>();
        headers.forEach((key, value) -> headerMap.put(key, String.join(",", value)));

        return apiGatewayService.routeRequest(path, method, headerMap, body);
    }

    @PostMapping("/routes")
    public Mono<ApiResponse<Void>> registerRoute(
            @RequestBody @Valid Map<String, Object> request) {
        String path = (String) request.get("path");
        String targetService = (String) request.get("targetService");
        String protocol = (String) request.getOrDefault("protocol", "HTTP");
        return apiGatewayService.registerRoute(path, targetService, protocol);
    }

    @DeleteMapping("/routes")
    public Mono<ApiResponse<Void>> removeRoute(@RequestParam String path) {
        return apiGatewayService.removeRoute(path);
    }

    @GetMapping("/routes")
    public Mono<ApiResponse<Map<String, Object>>> getRouteConfig(@RequestParam String path) {
        return apiGatewayService.getRouteConfig(path);
    }
}
