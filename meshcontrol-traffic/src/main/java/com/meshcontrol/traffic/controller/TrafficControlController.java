package com.meshcontrol.traffic.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.meshcontrol.common.response.ApiResponse;
import com.meshcontrol.common.response.PageResponse;
import com.meshcontrol.traffic.dto.*;
import com.meshcontrol.traffic.entity.CanaryRelease;
import com.meshcontrol.traffic.entity.TrafficPolicy;
import com.meshcontrol.traffic.service.TrafficControlService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/traffic")
@RequiredArgsConstructor
public class TrafficControlController {

    private final TrafficControlService trafficControlService;

    @PostMapping("/policies")
    public Mono<ApiResponse<TrafficPolicy>> createPolicy(@Valid @RequestBody TrafficPolicyRequest request) {
        return Mono.just(ApiResponse.created(trafficControlService.createPolicy(request)));
    }

    @GetMapping("/policies")
    public Mono<ApiResponse<PageResponse<TrafficPolicy>>> listPolicies(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String serviceName,
            @RequestParam(required = false) String namespace,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        IPage<TrafficPolicy> page = trafficControlService.listPolicies(type, serviceName, namespace, pageNum, pageSize);
        return Mono.just(ApiResponse.success(PageResponse.of(page)));
    }

    @GetMapping("/policies/{policyId}")
    public Mono<ApiResponse<TrafficPolicy>> getPolicy(@PathVariable String policyId) {
        return Mono.just(ApiResponse.success(trafficControlService.getPolicy(policyId)));
    }

    @PutMapping("/policies/{policyId}")
    public Mono<ApiResponse<Boolean>> updatePolicy(@PathVariable String policyId,
                                                   @Valid @RequestBody TrafficPolicyRequest request) {
        return Mono.just(ApiResponse.success(trafficControlService.updatePolicy(policyId, request)));
    }

    @DeleteMapping("/policies/{policyId}")
    public Mono<ApiResponse<Boolean>> deletePolicy(@PathVariable String policyId) {
        return Mono.just(ApiResponse.success(trafficControlService.deletePolicy(policyId)));
    }

    @GetMapping("/policies/effective/{serviceName}")
    public Mono<ApiResponse<Map<String, Object>>> getEffectivePolicies(@PathVariable String serviceName) {
        return Mono.just(ApiResponse.success(trafficControlService.getEffectivePolicies(serviceName)));
    }

    @PostMapping("/canary")
    public Mono<ApiResponse<CanaryRelease>> startCanaryRelease(@Valid @RequestBody CanaryReleaseRequest request) {
        return Mono.just(ApiResponse.created(trafficControlService.startCanaryRelease(request)));
    }

    @PutMapping("/canary/progress")
    public Mono<ApiResponse<Boolean>> updateCanaryProgress(@Valid @RequestBody CanaryProgressRequest request) {
        return Mono.just(ApiResponse.success(trafficControlService.updateCanaryProgress(request)));
    }

    @PostMapping("/canary/{releaseId}/complete")
    public Mono<ApiResponse<Boolean>> completeCanaryRelease(@PathVariable String releaseId) {
        return Mono.just(ApiResponse.success(trafficControlService.completeCanaryRelease(releaseId)));
    }

    @PostMapping("/canary/{releaseId}/rollback")
    public Mono<ApiResponse<Boolean>> rollbackCanaryRelease(@PathVariable String releaseId) {
        return Mono.just(ApiResponse.success(trafficControlService.rollbackCanaryRelease(releaseId)));
    }

    @GetMapping("/canary")
    public Mono<ApiResponse<List<CanaryRelease>>> listCanaryReleases(
            @RequestParam(required = false) String serviceName,
            @RequestParam(required = false) String status) {
        return Mono.just(ApiResponse.success(trafficControlService.listCanaryReleases(serviceName, status)));
    }

    @PostMapping("/bluegreen")
    public Mono<ApiResponse<Map<String, Object>>> startBlueGreenDeployment(@Valid @RequestBody BlueGreenDeployRequest request) {
        return Mono.just(ApiResponse.created(trafficControlService.startBlueGreenDeployment(request)));
    }

    @PostMapping("/bluegreen/{policyId}/switch/{targetVersion}")
    public Mono<ApiResponse<Map<String, Object>>> switchBlueGreenTraffic(
            @PathVariable String policyId,
            @PathVariable String targetVersion) {
        return Mono.just(ApiResponse.success(trafficControlService.switchBlueGreenTraffic(policyId, targetVersion)));
    }
}
