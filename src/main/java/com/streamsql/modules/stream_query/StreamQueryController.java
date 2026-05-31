package com.streamsql.modules.stream_query;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.streamsql.common.ApiResponse;
import com.streamsql.common.PageResult;
import com.streamsql.dto.StreamQueryDTO;
import com.streamsql.entity.StreamQueryPlan;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/query")
@RequiredArgsConstructor
public class StreamQueryController {

    private final StreamQueryParserService queryParserService;

    @PostMapping("/parse")
    public Mono<ApiResponse<StreamQueryPlan>> parseQuery(@Validated @RequestBody StreamQueryDTO dto) throws JsonProcessingException {
        return Mono.just(ApiResponse.success(queryParserService.parseAndPlan(dto)));
    }

    @GetMapping("/plans/{planId}")
    public Mono<ApiResponse<StreamQueryPlan>> getPlan(@PathVariable String planId) {
        return Mono.just(ApiResponse.success(queryParserService.getPlan(planId)));
    }

    @GetMapping("/plans")
    public Mono<ApiResponse<PageResult<StreamQueryPlan>>> listPlans(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status) {
        return Mono.just(ApiResponse.success(queryParserService.listPlans(page, size, status)));
    }

    @DeleteMapping("/plans/{planId}")
    public Mono<ApiResponse<Void>> deletePlan(@PathVariable String planId) {
        queryParserService.deletePlan(planId);
        return Mono.just(ApiResponse.success(null));
    }

    @PostMapping("/plans/{planId}/execute")
    public Mono<ApiResponse<StreamQueryPlan>> executePlan(@PathVariable String planId) {
        return Mono.just(ApiResponse.success(queryParserService.executePlan(planId)));
    }
}
