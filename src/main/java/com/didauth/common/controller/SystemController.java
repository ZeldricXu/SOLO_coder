package com.didauth.common.controller;

import com.didauth.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/system")
@RequiredArgsConstructor
public class SystemController {

    private final BuildProperties buildProperties;

    @GetMapping("/health")
    public Mono<ApiResponse<String>> health() {
        return Mono.just(ApiResponse.success("UP"));
    }

    @GetMapping("/info")
    public Mono<ApiResponse<Map<String, Object>>> info() {
        Map<String, Object> info = new HashMap<>();
        info.put("name", buildProperties.getName());
        info.put("version", buildProperties.getVersion());
        info.put("time", buildProperties.getTime());
        return Mono.just(ApiResponse.success(info));
    }

    @GetMapping("/metrics")
    public Mono<ApiResponse<Map<String, Object>>> metrics() {
        Map<String, Object> metrics = new HashMap<>();

        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();

        metrics.put("heap_used", memoryBean.getHeapMemoryUsage().getUsed());
        metrics.put("heap_max", memoryBean.getHeapMemoryUsage().getMax());
        metrics.put("heap_committed", memoryBean.getHeapMemoryUsage().getCommitted());
        metrics.put("non_heap_used", memoryBean.getNonHeapMemoryUsage().getUsed());
        metrics.put("system_load", osBean.getSystemLoadAverage());
        metrics.put("available_processors", osBean.getAvailableProcessors());
        metrics.put("uptime", ManagementFactory.getRuntimeMXBean().getUptime());

        return Mono.just(ApiResponse.success(metrics));
    }
}
