package com.tsdbproxy.quality.controller;

import com.tsdbproxy.common.entity.QualityRule;
import com.tsdbproxy.common.result.Result;
import com.tsdbproxy.quality.dto.QualityCheckRequest;
import com.tsdbproxy.quality.dto.QualityCheckResult;
import com.tsdbproxy.quality.dto.QualityRuleCreateRequest;
import com.tsdbproxy.quality.service.QualityCheckService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/quality")
@RequiredArgsConstructor
public class QualityController {

    private final QualityCheckService qualityCheckService;

    @PostMapping("/rules")
    public Mono<Result<QualityRule>> createRule(@RequestBody QualityRuleCreateRequest request) {
        return qualityCheckService.createRule(request)
                .map(Result::success);
    }

    @PostMapping("/check")
    public Mono<Result<QualityCheckResult>> check(@RequestBody QualityCheckRequest request) {
        return qualityCheckService.check(request)
                .map(Result::success);
    }
}
