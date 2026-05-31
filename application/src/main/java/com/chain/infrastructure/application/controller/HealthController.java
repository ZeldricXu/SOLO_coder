package com.chain.infrastructure.application.controller;

import com.chain.infrastructure.common.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

    @GetMapping("/health")
    public Mono<ApiResponse<Map<String, Object>>> health() {
        Map<String, Object> status = new HashMap<>();
        status.put("status", "UP");
        status.put("timestamp", System.currentTimeMillis());
        status.put("service", "chain-infrastructure");
        status.put("version", "1.0.0");
        return Mono.just(ApiResponse.success(status));
    }
}
