package com.ratelimiter.controller;

import com.ratelimiter.model.TrafficStatistics;
import com.ratelimiter.model.dto.ApiResponse;
import com.ratelimiter.service.stats.AggregatedStatsData;
import com.ratelimiter.service.stats.AggregationDimension;
import com.ratelimiter.service.stats.StatisticsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/stats")
public class StatisticsController {
    
    private final StatisticsService statisticsService;
    
    public StatisticsController(StatisticsService statisticsService) {
        this.statisticsService = statisticsService;
    }
    
    @GetMapping("/{target}")
    public ResponseEntity<ApiResponse<TrafficStatistics>> getStatistics(@PathVariable String target) {
        TrafficStatistics stats = statisticsService.getCurrentStatistics(target);
        
        if (stats == null) {
            return ResponseEntity.ok(ApiResponse.error(404, "No statistics found for target: " + target));
        }
        
        return ResponseEntity.ok(ApiResponse.success(stats));
    }
    
    @GetMapping("/all")
    public ResponseEntity<ApiResponse<List<TrafficStatistics>>> getAllStatistics() {
        List<TrafficStatistics> statsList = statisticsService.getAllCurrentStatistics();
        return ResponseEntity.ok(ApiResponse.success(statsList));
    }
    
    @GetMapping("/aggregated")
    public ResponseEntity<ApiResponse<List<AggregatedStatsData>>> getAllAggregatedStatistics() {
        List<AggregatedStatsData> statsList = statisticsService.getAllAggregatedStatistics();
        return ResponseEntity.ok(ApiResponse.success(statsList));
    }
    
    @GetMapping("/aggregated/by-dimension")
    public ResponseEntity<ApiResponse<List<AggregatedStatsData>>> getAggregatedByDimension(
            @RequestParam String dimensionCode,
            @RequestParam String value) {
        
        AggregationDimension dimension = AggregationDimension.fromCode(dimensionCode);
        if (dimension == null) {
            return ResponseEntity.ok(ApiResponse.error(400, "Invalid dimension code: " + dimensionCode));
        }
        
        List<AggregatedStatsData> statsList = statisticsService.getAggregatedStatisticsByDimension(dimension, value);
        return ResponseEntity.ok(ApiResponse.success(statsList));
    }
    
    @GetMapping("/dimensions")
    public ResponseEntity<ApiResponse<List<AggregationDimension>>> getEnabledDimensions() {
        List<AggregationDimension> dimensions = statisticsService.getEnabledDimensions();
        return ResponseEntity.ok(ApiResponse.success(dimensions));
    }
    
    @PostMapping("/dimensions/{dimensionCode}/enable")
    public ResponseEntity<ApiResponse<Void>> enableDimension(@PathVariable String dimensionCode) {
        AggregationDimension dimension = AggregationDimension.fromCode(dimensionCode);
        if (dimension == null) {
            return ResponseEntity.ok(ApiResponse.error(400, "Invalid dimension code: " + dimensionCode));
        }
        
        statisticsService.enableDimension(dimension);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
    
    @PostMapping("/dimensions/{dimensionCode}/disable")
    public ResponseEntity<ApiResponse<Void>> disableDimension(@PathVariable String dimensionCode) {
        AggregationDimension dimension = AggregationDimension.fromCode(dimensionCode);
        if (dimension == null) {
            return ResponseEntity.ok(ApiResponse.error(400, "Invalid dimension code: " + dimensionCode));
        }
        
        statisticsService.disableDimension(dimension);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
    
    @GetMapping("/dimensions/all")
    public ResponseEntity<ApiResponse<Map<String, String>>> getAllAvailableDimensions() {
        Map<String, String> dimensions = new HashMap<>();
        for (AggregationDimension dimension : AggregationDimension.values()) {
            dimensions.put(dimension.getCode(), dimension.getDescription());
        }
        return ResponseEntity.ok(ApiResponse.success(dimensions));
    }
}