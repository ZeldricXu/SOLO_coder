package com.streamsql.modules.data_quality;

import com.streamsql.common.ApiResponse;
import com.streamsql.common.PageResult;
import com.streamsql.dto.QualityRuleDTO;
import com.streamsql.entity.AnomalyDataRecord;
import com.streamsql.entity.QualityCheckResult;
import com.streamsql.entity.QualityRule;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/v1/quality")
@RequiredArgsConstructor
public class QualityRuleController {

    private final QualityRuleService qualityRuleService;

    @PostMapping("/rules")
    public Mono<ApiResponse<QualityRule>> createRule(@Validated @RequestBody QualityRuleDTO dto) {
        return Mono.just(ApiResponse.created(qualityRuleService.createRule(dto)));
    }

    @PutMapping("/rules/{ruleId}")
    public Mono<ApiResponse<QualityRule>> updateRule(
            @PathVariable String ruleId,
            @Validated @RequestBody QualityRuleDTO dto) {
        return Mono.just(ApiResponse.success(qualityRuleService.updateRule(ruleId, dto)));
    }

    @DeleteMapping("/rules/{ruleId}")
    public Mono<ApiResponse<Void>> deleteRule(@PathVariable String ruleId) {
        qualityRuleService.deleteRule(ruleId);
        return Mono.just(ApiResponse.success(null));
    }

    @GetMapping("/rules/{ruleId}")
    public Mono<ApiResponse<QualityRule>> getRule(@PathVariable String ruleId) {
        return Mono.just(ApiResponse.success(qualityRuleService.getRule(ruleId)));
    }

    @GetMapping("/rules")
    public Mono<ApiResponse<PageResult<QualityRule>>> listRules(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String datasourceId,
            @RequestParam(required = false) String ruleType) {
        return Mono.just(ApiResponse.success(qualityRuleService.listRules(page, size, datasourceId, ruleType)));
    }

    @PostMapping("/rules/{ruleId}/execute")
    public Mono<ApiResponse<QualityCheckResult>> executeCheck(@PathVariable String ruleId) {
        return Mono.just(ApiResponse.success(qualityRuleService.executeQualityCheck(ruleId)));
    }

    @GetMapping("/rules/{ruleId}/results")
    public Mono<ApiResponse<List<QualityCheckResult>>> getCheckResults(
            @PathVariable String ruleId,
            @RequestParam(defaultValue = "10") int limit) {
        return Mono.just(ApiResponse.success(qualityRuleService.getCheckResults(ruleId, limit)));
    }

    @GetMapping("/anomalies")
    public Mono<ApiResponse<PageResult<AnomalyDataRecord>>> getAnomalies(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String ruleId,
            @RequestParam(required = false) String datasourceId) {
        return Mono.just(ApiResponse.success(qualityRuleService.getAnomalyRecords(page, size, ruleId, datasourceId)));
    }
}
