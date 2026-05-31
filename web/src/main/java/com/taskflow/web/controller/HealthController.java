package com.taskflow.web.controller;

import com.taskflow.common.model.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping
public class HealthController {

    @GetMapping("/health")
    public Mono<Result<Map<String, Object>>> health() {
        return Mono.just(Result.success(Map.of(
                "status", "UP",
                "version", "1.0.0",
                "timestamp", System.currentTimeMillis()
        )));
    }

    @GetMapping("/")
    public Mono<Result<Map<String, Object>>> index() {
        return Mono.just(Result.success(Map.of(
                "name", "TaskFlow Platform",
                "version", "1.0.0",
                "description", "企业级任务调度与执行管理平台",
                "docs", "/swagger-ui.html",
                "health", "/health",
                "actuator", "/actuator"
        )));
    }
}
