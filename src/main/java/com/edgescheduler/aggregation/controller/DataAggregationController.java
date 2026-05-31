package com.edgescheduler.aggregation.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.edgescheduler.aggregation.dto.DataCollectRequest;
import com.edgescheduler.aggregation.dto.DataStreamDTO;
import com.edgescheduler.aggregation.entity.DataAggregationResult;
import com.edgescheduler.aggregation.entity.DataStream;
import com.edgescheduler.aggregation.service.DataAggregationService;
import com.edgescheduler.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/aggregation")
@RequiredArgsConstructor
public class DataAggregationController {

    private final DataAggregationService aggregationService;

    @PostMapping("/streams")
    public Mono<ApiResponse<DataStreamDTO>> createDataStream(@Valid @RequestBody DataStreamDTO dto) {
        return Mono.just(ApiResponse.created(aggregationService.createDataStream(dto)));
    }

    @GetMapping("/streams/{streamId}")
    public Mono<ApiResponse<DataStreamDTO>> getDataStream(@PathVariable String streamId) {
        return Mono.just(ApiResponse.success(aggregationService.getDataStream(streamId)));
    }

    @GetMapping("/streams")
    public Mono<ApiResponse<IPage<DataStreamDTO>>> listDataStreams(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String deviceKey,
            @RequestParam(required = false) Integer enabled) {
        Page<DataStream> pageParam = new Page<>(page, size);
        return Mono.just(ApiResponse.success(aggregationService.listDataStreams(pageParam, deviceKey, enabled)));
    }

    @PutMapping("/streams/{streamId}")
    public Mono<ApiResponse<DataStreamDTO>> updateDataStream(
            @PathVariable String streamId,
            @Valid @RequestBody DataStreamDTO dto) {
        return Mono.just(ApiResponse.success(aggregationService.updateDataStream(streamId, dto)));
    }

    @DeleteMapping("/streams/{streamId}")
    public Mono<ApiResponse<Void>> deleteDataStream(@PathVariable String streamId) {
        aggregationService.deleteDataStream(streamId);
        return Mono.just(ApiResponse.success(null));
    }

    @PostMapping("/collect")
    public Mono<ApiResponse<Void>> collectData(@Valid @RequestBody DataCollectRequest request) {
        aggregationService.collectData(request);
        return Mono.just(ApiResponse.success(null));
    }

    @PostMapping("/process")
    public Mono<ApiResponse<Void>> processAggregation() {
        aggregationService.processAggregation();
        return Mono.just(ApiResponse.success(null));
    }

    @PostMapping("/calculate")
    public Mono<ApiResponse<Map<String, Object>>> calculateAggregation(
            @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        String type = (String) body.get("type");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) body.get("data");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fieldsConfig = (List<Map<String, Object>>) body.get("fieldsConfig");
        return Mono.just(ApiResponse.success(aggregationService.calculateAggregation(type, data, fieldsConfig)));
    }

    @GetMapping("/streams/{streamId}/results")
    public Mono<ApiResponse<List<DataAggregationResult>>> getAggregationResults(
            @PathVariable String streamId,
            @RequestParam(defaultValue = "20") int limit) {
        return Mono.just(ApiResponse.success(aggregationService.getAggregationResults(streamId, limit)));
    }

    @GetMapping("/streams/{streamId}/results/range")
    public Mono<ApiResponse<List<DataAggregationResult>>> getAggregationResultsByTimeRange(
            @PathVariable String streamId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return Mono.just(ApiResponse.success(
                aggregationService.getAggregationResultsByTimeRange(streamId, startTime, endTime)));
    }

    @PostMapping("/upload")
    public Mono<ApiResponse<Void>> uploadAggregationResults() {
        aggregationService.uploadAggregationResults();
        return Mono.just(ApiResponse.success(null));
    }

    @GetMapping("/streams/{streamId}/statistics")
    public Mono<ApiResponse<Map<String, Object>>> getAggregationStatistics(@PathVariable String streamId) {
        return Mono.just(ApiResponse.success(aggregationService.getAggregationStatistics(streamId)));
    }
}
