package com.apishield.masking.controller;

import com.apishield.common.dto.Result;
import com.apishield.masking.domain.MaskingPolicy;
import com.apishield.masking.dto.MaskingPolicyRequest;
import com.apishield.masking.dto.MaskingRequest;
import com.apishield.masking.service.DataMaskingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/masking")
@RequiredArgsConstructor
public class DataMaskingController {

    private final DataMaskingService maskingService;

    @PostMapping("/policies")
    public Mono<Result<MaskingPolicy>> createPolicy(@RequestBody MaskingPolicyRequest request) {
        return Mono.just(Result.success(maskingService.createPolicy(request)));
    }

    @GetMapping("/policies/{policyId}")
    public Mono<Result<MaskingPolicy>> getPolicy(@PathVariable String policyId) {
        return Mono.just(Result.success(maskingService.getPolicy(policyId)));
    }

    @GetMapping("/policies")
    public Mono<Result<List<MaskingPolicy>>> getAllPolicies() {
        return Mono.just(Result.success(maskingService.getAllPolicies()));
    }

    @GetMapping("/policies/table/{dataSource}/{tableName}")
    public Mono<Result<List<MaskingPolicy>>> getPoliciesForTable(
            @PathVariable String dataSource,
            @PathVariable String tableName) {
        return Mono.just(Result.success(maskingService.getPoliciesForTable(dataSource, tableName)));
    }

    @PutMapping("/policies/{policyId}")
    public Mono<Result<MaskingPolicy>> updatePolicy(
            @PathVariable String policyId,
            @RequestBody MaskingPolicyRequest request) {
        return Mono.just(Result.success(maskingService.updatePolicy(policyId, request)));
    }

    @DeleteMapping("/policies/{policyId}")
    public Mono<Result<Void>> deletePolicy(@PathVariable String policyId) {
        maskingService.deletePolicy(policyId);
        return Mono.just(Result.success(null));
    }

    @PostMapping("/policies/{policyId}/enable")
    public Mono<Result<MaskingPolicy>> enablePolicy(@PathVariable String policyId) {
        return Mono.just(Result.success(maskingService.enablePolicy(policyId)));
    }

    @PostMapping("/policies/{policyId}/disable")
    public Mono<Result<MaskingPolicy>> disablePolicy(@PathVariable String policyId) {
        return Mono.just(Result.success(maskingService.disablePolicy(policyId)));
    }

    @PostMapping("/mask")
    public Mono<Result<Map<String, Object>>> maskData(@RequestBody MaskingRequest request) {
        return Mono.just(Result.success(maskingService.maskData(request)));
    }

    @PostMapping("/should-mask")
    public Mono<Result<Boolean>> shouldMask(@RequestBody MaskingRequest request) {
        boolean shouldMask = request.getData().keySet().stream()
                .anyMatch(col -> maskingService.shouldMask(
                        request.getDataSource(),
                        request.getTableName(),
                        col,
                        request.getUserContext()));
        return Mono.just(Result.success(shouldMask));
    }
}
