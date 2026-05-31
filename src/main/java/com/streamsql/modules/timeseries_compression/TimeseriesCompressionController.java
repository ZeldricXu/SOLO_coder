package com.streamsql.modules.timeseries_compression;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.streamsql.common.ApiResponse;
import com.streamsql.common.PageResult;
import com.streamsql.dto.TimeseriesDataDTO;
import com.streamsql.entity.TimeseriesData;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/timeseries")
@RequiredArgsConstructor
public class TimeseriesCompressionController {

    private final TimeseriesCompressionService timeseriesService;

    @PostMapping("/data")
    public Mono<ApiResponse<TimeseriesData>> insertData(@Validated @RequestBody TimeseriesDataDTO dto) throws JsonProcessingException {
        return Mono.just(ApiResponse.created(timeseriesService.insertData(dto)));
    }

    @PostMapping("/data/batch")
    public Mono<ApiResponse<Integer>> insertBatchData(@Validated @RequestBody List<TimeseriesDataDTO> batch) throws JsonProcessingException {
        int count = 0;
        for (TimeseriesDataDTO dto : batch) {
            timeseriesService.insertData(dto);
            count++;
        }
        return Mono.just(ApiResponse.created(count));
    }

    @GetMapping("/data")
    public Mono<ApiResponse<PageResult<TimeseriesData>>> queryData(
            @RequestParam String metricName,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @RequestParam(required = false) String resolution,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "100") int size) {
        return Mono.just(ApiResponse.success(
                timeseriesService.queryData(metricName, startTime, endTime, resolution, page, size)));
    }

    @GetMapping("/statistics")
    public Mono<ApiResponse<Map<String, Object>>> getStatistics(
            @RequestParam String metricName,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return Mono.just(ApiResponse.success(timeseriesService.getStatistics(metricName, startTime, endTime)));
    }

    @PostMapping("/compress")
    public Mono<ApiResponse<Void>> triggerCompression() {
        timeseriesService.compressOldData();
        return Mono.just(ApiResponse.success(null));
    }

    @PostMapping("/downsample")
    public Mono<ApiResponse<Void>> triggerDownsampling() {
        timeseriesService.performDownsampling();
        return Mono.just(ApiResponse.success(null));
    }

    @DeleteMapping("/data")
    public Mono<ApiResponse<Void>> deleteData(
            @RequestParam String metricName,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime beforeTime) {
        timeseriesService.deleteData(metricName, beforeTime);
        return Mono.just(ApiResponse.success(null));
    }
}
