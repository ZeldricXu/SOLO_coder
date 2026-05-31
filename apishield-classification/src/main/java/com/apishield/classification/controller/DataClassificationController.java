package com.apishield.classification.controller;

import com.apishield.common.dto.Result;
import com.apishield.classification.domain.ClassificationPolicy;
import com.apishield.classification.domain.DataClassification;
import com.apishield.classification.domain.ScanJob;
import com.apishield.classification.dto.PolicyRequest;
import com.apishield.classification.dto.ScanJobRequest;
import com.apishield.classification.service.DataClassificationService;
import com.apishield.domain.vo.SecurityLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/classification")
@RequiredArgsConstructor
public class DataClassificationController {

    private final DataClassificationService classificationService;

    @PostMapping("/scan-jobs")
    public Mono<Result<ScanJob>> createScanJob(@RequestBody ScanJobRequest request) {
        return Mono.just(Result.success(classificationService.createScanJob(request)));
    }

    @PostMapping("/scan-jobs/{jobId}/start")
    public Mono<Result<ScanJob>> startScanJob(@PathVariable String jobId) {
        return Mono.just(Result.success(classificationService.startScanJob(jobId)));
    }

    @GetMapping("/scan-jobs/{jobId}")
    public Mono<Result<ScanJob>> getScanJob(@PathVariable String jobId) {
        return Mono.just(Result.success(classificationService.getScanJob(jobId)));
    }

    @GetMapping("/scan-jobs/{jobId}/results")
    public Mono<Result<List<DataClassification>>> getClassificationResults(@PathVariable String jobId) {
        return Mono.just(Result.success(classificationService.getClassificationResults(jobId)));
    }

    @GetMapping("/results/data-source/{dataSource}")
    public Mono<Result<List<DataClassification>>> getClassificationsByDataSource(@PathVariable String dataSource) {
        return Mono.just(Result.success(classificationService.getClassificationsByDataSource(dataSource)));
    }

    @GetMapping("/results/level/{level}")
    public Mono<Result<List<DataClassification>>> getClassificationsByLevel(@PathVariable SecurityLevel level) {
        return Mono.just(Result.success(classificationService.getClassificationsByLevel(level)));
    }

    @PostMapping("/policies")
    public Mono<Result<ClassificationPolicy>> createPolicy(@RequestBody PolicyRequest request) {
        return Mono.just(Result.success(classificationService.createPolicy(request)));
    }

    @GetMapping("/policies/{policyId}")
    public Mono<Result<ClassificationPolicy>> getPolicy(@PathVariable String policyId) {
        return Mono.just(Result.success(classificationService.getPolicy(policyId)));
    }

    @GetMapping("/policies")
    public Mono<Result<List<ClassificationPolicy>>> getAllPolicies() {
        return Mono.just(Result.success(classificationService.getAllPolicies()));
    }

    @PutMapping("/policies/{policyId}")
    public Mono<Result<ClassificationPolicy>> updatePolicy(
            @PathVariable String policyId,
            @RequestBody PolicyRequest request) {
        return Mono.just(Result.success(classificationService.updatePolicy(policyId, request)));
    }

    @DeleteMapping("/policies/{policyId}")
    public Mono<Result<Void>> deletePolicy(@PathVariable String policyId) {
        classificationService.deletePolicy(policyId);
        return Mono.just(Result.success(null));
    }

    @PostMapping("/results/{classificationId}/apply-policy/{policyId}")
    public Mono<Result<Void>> applyPolicyToClassification(
            @PathVariable String classificationId,
            @PathVariable String policyId) {
        classificationService.applyPolicyToClassification(classificationId, policyId);
        return Mono.just(Result.success(null));
    }

    @GetMapping("/tables/{tableName}/sensitive-fields")
    public Mono<Result<Map<String, SecurityLevel>>> getSensitiveFields(@PathVariable String tableName) {
        return Mono.just(Result.success(classificationService.getSensitiveFields(tableName)));
    }
}
