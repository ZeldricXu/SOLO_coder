package com.meshcontrol.sidecar.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.meshcontrol.common.response.ApiResponse;
import com.meshcontrol.common.response.PageResponse;
import com.meshcontrol.sidecar.dto.*;
import com.meshcontrol.sidecar.entity.InjectionPolicy;
import com.meshcontrol.sidecar.entity.SidecarConfig;
import com.meshcontrol.sidecar.entity.SidecarInstance;
import com.meshcontrol.sidecar.service.SidecarLifecycleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/v1/sidecars")
@RequiredArgsConstructor
public class SidecarLifecycleController {

    private final SidecarLifecycleService sidecarLifecycleService;

    @PostMapping("/inject")
    public Mono<ApiResponse<SidecarInstance>> injectSidecar(@Valid @RequestBody SidecarInjectRequest request) {
        return Mono.just(ApiResponse.created(sidecarLifecycleService.injectSidecar(request)));
    }

    @DeleteMapping("/{sidecarId}")
    public Mono<ApiResponse<Boolean>> removeSidecar(@PathVariable String sidecarId) {
        return Mono.just(ApiResponse.success(sidecarLifecycleService.removeSidecar(sidecarId)));
    }

    @PostMapping("/{sidecarId}/heartbeat")
    public Mono<ApiResponse<Boolean>> heartbeat(@PathVariable String sidecarId) {
        return Mono.just(ApiResponse.success(sidecarLifecycleService.heartbeat(sidecarId)));
    }

    @GetMapping
    public Mono<ApiResponse<PageResponse<SidecarInstance>>> listSidecars(
            @RequestParam(required = false) String namespace,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        IPage<SidecarInstance> page = sidecarLifecycleService.listSidecars(namespace, status, pageNum, pageSize);
        return Mono.just(ApiResponse.success(PageResponse.of(page)));
    }

    @GetMapping("/{sidecarId}")
    public Mono<ApiResponse<SidecarInstance>> getSidecar(@PathVariable String sidecarId) {
        return Mono.just(ApiResponse.success(sidecarLifecycleService.getSidecar(sidecarId)));
    }

    @PutMapping("/resources")
    public Mono<ApiResponse<Boolean>> updateResourceLimits(@Valid @RequestBody ResourceLimitUpdateRequest request) {
        return Mono.just(ApiResponse.success(sidecarLifecycleService.updateResourceLimits(request)));
    }

    @PostMapping("/policies")
    public Mono<ApiResponse<InjectionPolicy>> createInjectionPolicy(@Valid @RequestBody InjectionPolicyRequest request) {
        return Mono.just(ApiResponse.created(sidecarLifecycleService.createInjectionPolicy(request)));
    }

    @GetMapping("/policies")
    public Mono<ApiResponse<List<InjectionPolicy>>> listInjectionPolicies(
            @RequestParam(required = false) String namespace) {
        return Mono.just(ApiResponse.success(sidecarLifecycleService.listInjectionPolicies(namespace)));
    }

    @DeleteMapping("/policies/{policyId}")
    public Mono<ApiResponse<Boolean>> deleteInjectionPolicy(@PathVariable String policyId) {
        return Mono.just(ApiResponse.success(sidecarLifecycleService.deleteInjectionPolicy(policyId)));
    }

    @PostMapping("/configs")
    public Mono<ApiResponse<SidecarConfig>> publishConfig(@Valid @RequestBody ConfigUpdateRequest request) {
        return Mono.just(ApiResponse.created(sidecarLifecycleService.publishConfig(request)));
    }

    @GetMapping("/configs/{namespace}/latest")
    public Mono<ApiResponse<SidecarConfig>> getLatestConfig(@PathVariable String namespace) {
        return Mono.just(ApiResponse.success(sidecarLifecycleService.getLatestConfig(namespace)));
    }
}
