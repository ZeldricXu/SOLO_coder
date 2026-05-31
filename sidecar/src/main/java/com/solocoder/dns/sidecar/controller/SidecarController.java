package com.solocoder.dns.sidecar.controller;

import com.solocoder.dns.common.model.ApiResponse;
import com.solocoder.dns.sidecar.model.SidecarInjectionPolicy;
import com.solocoder.dns.sidecar.model.SidecarInstance;
import com.solocoder.dns.sidecar.service.SidecarLifecycleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/sidecar")
@RequiredArgsConstructor
public class SidecarController {
    private final SidecarLifecycleService sidecarService;

    @PostMapping("/instances")
    public ApiResponse<SidecarInstance> register(@RequestBody SidecarInstance instance) {
        return ApiResponse.success(201, sidecarService.registerInstance(instance));
    }

    @GetMapping("/instances")
    public ApiResponse<List<SidecarInstance>> list(@RequestParam(required = false) String serviceName) {
        return ApiResponse.success(sidecarService.listInstances(serviceName));
    }

    @GetMapping("/instances/{id}")
    public ApiResponse<SidecarInstance> get(@PathVariable String id) {
        return ApiResponse.success(sidecarService.getInstance(id));
    }

    @PostMapping("/instances/{id}/heartbeat")
    public ApiResponse<Void> heartbeat(@PathVariable String id) {
        sidecarService.heartbeat(id);
        return ApiResponse.success(null);
    }

    @PutMapping("/instances/{id}/config")
    public ApiResponse<Void> updateConfig(@PathVariable String id, @RequestBody Map<String, String> body) {
        sidecarService.updateConfig(id, body.get("configHash"));
        return ApiResponse.success(null);
    }

    @PutMapping("/instances/{id}/resources")
    public ApiResponse<Void> updateResources(@PathVariable String id, @RequestBody Map<String, Double> body) {
        sidecarService.updateResourceLimits(id, body.get("cpuLimit"), body.get("memoryLimit"));
        return ApiResponse.success(null);
    }

    @PostMapping("/instances/{id}/reload")
    public ApiResponse<Void> hotReload(@PathVariable String id) {
        sidecarService.hotReloadConfig(id);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/instances/{id}")
    public ApiResponse<Void> deregister(@PathVariable String id) {
        sidecarService.deregisterInstance(id);
        return ApiResponse.success(null);
    }

    @PostMapping("/policies")
    public ApiResponse<SidecarInjectionPolicy> createPolicy(@RequestBody SidecarInjectionPolicy policy) {
        return ApiResponse.success(201, sidecarService.createInjectionPolicy(policy));
    }
}
