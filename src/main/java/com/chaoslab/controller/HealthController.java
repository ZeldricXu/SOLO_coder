package com.chaoslab.controller;

import com.chaoslab.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

    @GetMapping("/health")
    public Mono<ApiResponse<Map<String, Object>>> health() {
        return Mono.just(ApiResponse.success(Map.of(
                "status", "UP",
                "service", "ChaosLab Platform",
                "version", "1.0.0"
        )));
    }

    @GetMapping("/info")
    public Mono<ApiResponse<Map<String, Object>>> info() {
        return Mono.just(ApiResponse.success(Map.of(
                "name", "ChaosLab Platform",
                "description", "Enterprise Chaos Engineering Experiment Orchestration Platform",
                "version", "1.0.0",
                "modules", Map.of(
                        "sidecar", "Sidecar Lifecycle Management",
                        "mtls", "mTLS Certificate Management",
                        "dns", "DNS Proxy & Resolution",
                        "traffic", "Traffic Strategy Control",
                        "image", "Container Image Distribution",
                        "eventstore", "Event Store & Replay",
                        "faultinject", "Fault Injection Orchestration",
                        "audit", "Command Sourcing & Audit"
                )
        )));
    }
}
