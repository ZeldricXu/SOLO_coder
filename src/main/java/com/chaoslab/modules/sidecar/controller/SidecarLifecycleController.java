package com.chaoslab.modules.sidecar.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.chaoslab.common.ApiResponse;
import com.chaoslab.common.PageResult;
import com.chaoslab.entity.SidecarConfig;
import com.chaoslab.entity.SidecarInjectionPolicy;
import com.chaoslab.entity.SidecarInstance;
import com.chaoslab.modules.sidecar.dto.ConfigUpdateRequest;
import com.chaoslab.modules.sidecar.dto.InjectionPolicyCreateRequest;
import com.chaoslab.modules.sidecar.dto.ResourceLimitUpdateRequest;
import com.chaoslab.modules.sidecar.dto.SidecarInstanceStatusResponse;
import com.chaoslab.modules.sidecar.service.SidecarLifecycleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/sidecar")
@RequiredArgsConstructor
public class SidecarLifecycleController {

    private final SidecarLifecycleService sidecarService;

    @PostMapping("/policies")
    public Mono<ApiResponse<SidecarInjectionPolicy>> createPolicy(
            @Valid @RequestBody InjectionPolicyCreateRequest request) {
        return sidecarService.createInjectionPolicy(request)
                .map(ApiResponse::success);
    }

    @GetMapping("/policies")
    public Mono<ApiResponse<PageResult<SidecarInjectionPolicy>>> listPolicies(
            @RequestParam(required = false) String namespace,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return sidecarService.listPolicies(namespace, pageNum, pageSize)
                .map(page -> ApiResponse.success(new PageResult<>(
                        page.getRecords(),
                        page.getTotal(),
                        page.getCurrent(),
                        page.getSize()
                )));
    }

    @GetMapping("/policies/{policyId}")
    public Mono<ApiResponse<SidecarInjectionPolicy>> getPolicy(@PathVariable String policyId) {
        return sidecarService.getPolicy(policyId)
                .map(ApiResponse::success);
    }

    @PostMapping("/inject")
    public Mono<ApiResponse<SidecarInstance>> injectSidecar(
            @RequestBody Map<String, String> request) {
        String policyId = request.get("policyId");
        String targetPod = request.get("targetPod");
        String namespace = request.get("namespace");
        return sidecarService.injectSidecar(policyId, targetPod, namespace)
                .map(ApiResponse::success);
    }

    @PostMapping("/config")
    public Mono<ApiResponse<SidecarConfig>> updateConfig(@Valid @RequestBody ConfigUpdateRequest request) {
        return sidecarService.updateConfig(request)
                .map(ApiResponse::success);
    }

    @GetMapping("/instances/{instanceId}/config")
    public Mono<ApiResponse<SidecarConfig>> getAppliedConfig(@PathVariable String instanceId) {
        return sidecarService.getAppliedConfig(instanceId)
                .map(ApiResponse::success);
    }

    @PostMapping("/config/applied")
    public Mono<ApiResponse<Void>> confirmConfigApplied(@RequestBody Map<String, String> request) {
        String instanceId = request.get("instanceId");
        String configId = request.get("configId");
        return sidecarService.confirmConfigApplied(instanceId, configId)
                .then(Mono.just(ApiResponse.success()));
    }

    @PostMapping("/resources")
    public Mono<ApiResponse<SidecarInstanceStatusResponse>> updateResourceLimits(
            @Valid @RequestBody ResourceLimitUpdateRequest request) {
        return sidecarService.updateResourceLimits(request)
                .map(ApiResponse::success);
    }

    @GetMapping("/instances/{instanceId}/status")
    public Mono<ApiResponse<SidecarInstanceStatusResponse>> getInstanceStatus(@PathVariable String instanceId) {
        return sidecarService.getInstanceStatus(instanceId)
                .map(ApiResponse::success);
    }
}
