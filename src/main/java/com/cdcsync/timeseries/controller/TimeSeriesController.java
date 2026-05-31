package com.cdcsync.timeseries.controller;

import com.cdcsync.common.api.Result;
import com.cdcsync.timeseries.domain.TimeSeriesData;
import com.cdcsync.timeseries.service.TimeSeriesService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/time-series")
@RequiredArgsConstructor
public class TimeSeriesController {

    private final TimeSeriesService timeSeriesService;

    @PostMapping("/{configId}/data")
    public Result<Void> writeData(@PathVariable String configId, @Valid @RequestBody WriteDataRequest request) {
        timeSeriesService.writeData(configId, request.getTimestamp(), request.getValue(), request.getTags());
        return Result.success();
    }

    @GetMapping("/{configId}/data")
    public Result<List<TimeSeriesData>> queryData(
            @PathVariable String configId,
            @RequestParam long startTime,
            @RequestParam long endTime,
            @RequestParam(defaultValue = "RAW") String resolution) {
        return Result.success(timeSeriesService.queryData(configId, startTime, endTime, resolution));
    }

    @PostMapping("/{configId}/compress")
    public Result<Void> compressData(@PathVariable String configId) {
        timeSeriesService.compressData(configId);
        return Result.success();
    }

    @PostMapping("/{configId}/downsample")
    public Result<Void> downsampleData(@PathVariable String configId) {
        timeSeriesService.downsampleData(configId);
        return Result.success();
    }

    @PostMapping("/{configId}/purge")
    public Result<Void> purgeExpiredData(@PathVariable String configId) {
        timeSeriesService.purgeExpiredData(configId);
        return Result.success();
    }

    @Data
    public static class WriteDataRequest {

        @NotNull(message = "Timestamp cannot be null")
        private Long timestamp;

        @NotNull(message = "Value cannot be null")
        private Double value;

        private Map<String, String> tags;
    }
}
