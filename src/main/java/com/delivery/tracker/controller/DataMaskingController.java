package com.delivery.tracker.controller;

import com.delivery.tracker.common.Result;
import com.delivery.tracker.entity.DataMaskingRule;
import com.delivery.tracker.service.DataMaskingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/masking")
@RequiredArgsConstructor
public class DataMaskingController {

    private final DataMaskingService maskingService;

    @PostMapping("/apply")
    public Mono<Result<Map<String, Object>>> maskData(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) request.get("data");
        @SuppressWarnings("unchecked")
        Set<String> userRoles = Set.copyOf((List<String>) request.get("userRoles"));

        return maskingService.maskData(data, userRoles)
                .map(Result::success);
    }

    @PostMapping("/rules")
    public Mono<Result<DataMaskingRule>> createMaskingRule(@RequestBody DataMaskingRule rule) {
        return maskingService.createMaskingRule(rule)
                .map(Result::success);
    }

    @GetMapping("/rules")
    public Mono<Result<List<DataMaskingRule>>> getAllMaskingRules() {
        return maskingService.getAllMaskingRules()
                .collectList()
                .map(Result::success);
    }

    @PutMapping("/rules/{ruleId}")
    public Mono<Result<DataMaskingRule>> updateMaskingRule(
            @PathVariable String ruleId,
            @RequestBody DataMaskingRule rule) {
        return maskingService.updateMaskingRule(ruleId, rule)
                .map(Result::success);
    }

    @DeleteMapping("/rules/{ruleId}")
    public Mono<Result<Void>> deleteMaskingRule(@PathVariable String ruleId) {
        return maskingService.deleteMaskingRule(ruleId)
                .then(Mono.just(Result.success()));
    }
}
