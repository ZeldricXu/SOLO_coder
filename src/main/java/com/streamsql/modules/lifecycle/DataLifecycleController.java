package com.streamsql.modules.lifecycle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.streamsql.common.ApiResponse;
import com.streamsql.common.PageResult;
import com.streamsql.dto.LifecyclePolicyDTO;
import com.streamsql.entity.DataArchiveRecord;
import com.streamsql.entity.LifecyclePolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/lifecycle")
@RequiredArgsConstructor
public class DataLifecycleController {

    private final DataLifecycleService dataLifecycleService;

    @PostMapping("/policies")
    public Mono<ApiResponse<LifecyclePolicy>> createPolicy(@Validated @RequestBody LifecyclePolicyDTO dto) {
        return Mono.just(ApiResponse.created(dataLifecycleService.createPolicy(dto)));
    }

    @PutMapping("/policies/{policyId}")
    public Mono<ApiResponse<LifecyclePolicy>> updatePolicy(
            @PathVariable String policyId,
            @Validated @RequestBody LifecyclePolicyDTO dto) {
        return Mono.just(ApiResponse.success(dataLifecycleService.updatePolicy(policyId, dto)));
    }

    @DeleteMapping("/policies/{policyId}")
    public Mono<ApiResponse<Void>> deletePolicy(@PathVariable String policyId) {
        dataLifecycleService.deletePolicy(policyId);
        return Mono.just(ApiResponse.success(null));
    }

    @GetMapping("/policies/{policyId}")
    public Mono<ApiResponse<LifecyclePolicy>> getPolicy(@PathVariable String policyId) {
        return Mono.just(ApiResponse.success(dataLifecycleService.getPolicy(policyId)));
    }

    @GetMapping("/policies")
    public Mono<ApiResponse<PageResult<LifecyclePolicy>>> listPolicies(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String datasourceId,
            @RequestParam(required = false) Boolean enabled) {
        return Mono.just(ApiResponse.success(dataLifecycleService.listPolicies(page, size, datasourceId, enabled)));
    }

    @PostMapping("/policies/{policyId}/migrate")
    public Mono<ApiResponse<Void>> migrateToColdStorage(@PathVariable String policyId) throws JsonProcessingException {
        dataLifecycleService.migrateToColdStorage(policyId);
        return Mono.just(ApiResponse.success(null));
    }

    @PostMapping("/policies/{policyId}/cleanup")
    public Mono<ApiResponse<Void>> cleanupExpired(@PathVariable String policyId) throws JsonProcessingException {
        dataLifecycleService.cleanupExpired(policyId);
        return Mono.just(ApiResponse.success(null));
    }

    @PostMapping("/execute")
    public Mono<ApiResponse<Void>> executeAllPolicies() {
        dataLifecycleService.executeLifecyclePolicies();
        return Mono.just(ApiResponse.success(null));
    }

    @GetMapping("/archives")
    public Mono<ApiResponse<PageResult<DataArchiveRecord>>> listArchiveRecords(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String policyId,
            @RequestParam(required = false) String datasourceId) {
        return Mono.just(ApiResponse.success(dataLifecycleService.listArchiveRecords(page, size, policyId, datasourceId)));
    }

    @GetMapping("/statistics")
    public Mono<ApiResponse<Map<String, Object>>> getStorageStatistics(
            @RequestParam(required = false) String datasourceId) {
        return Mono.just(ApiResponse.success(dataLifecycleService.getStorageStatistics(datasourceId)));
    }
}
