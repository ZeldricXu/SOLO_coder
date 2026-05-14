package com.healthtrack.controller;

import com.healthtrack.dto.ApiResponse;
import com.healthtrack.entity.HealthData;
import com.healthtrack.entity.HealthHistory;
import com.healthtrack.entity.HealthStatistics;
import com.healthtrack.service.HistoryService;
import com.healthtrack.service.QueryService;
import com.healthtrack.service.StatisticsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/query")
public class QueryController {

    @Autowired
    private QueryService queryService;

    @Autowired
    private StatisticsService statisticsService;

    @Autowired
    private HistoryService historyService;

    @GetMapping("/data")
    public ResponseEntity<ApiResponse<List<HealthData>>> queryHealthData(
            @RequestParam String userId,
            @RequestParam(required = false) String dataType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        try {
            List<HealthData> data;
            
            if (dataType != null && !dataType.isEmpty() && startDate != null && endDate != null) {
                data = queryService.queryHealthDataByTypeAndDateRange(userId, dataType, startDate, endDate);
            } else if (dataType != null && !dataType.isEmpty()) {
                data = queryService.queryHealthDataByType(userId, dataType);
            } else if (startDate != null && endDate != null) {
                data = queryService.queryHealthDataByDateRange(userId, startDate, endDate);
            } else {
                data = queryService.queryHealthDataByUser(userId);
            }
            
            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(500, "查询数据失败: " + e.getMessage()));
        }
    }

    @GetMapping("/data/latest")
    public ResponseEntity<ApiResponse<HealthData>> getLatestData(
            @RequestParam String userId,
            @RequestParam String dataType) {
        try {
            Optional<HealthData> data = queryService.getLatestHealthData(userId, dataType);
            return data.map(d -> ResponseEntity.ok(ApiResponse.success(d)))
                    .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(500, "查询最新数据失败: " + e.getMessage()));
        }
    }

    @GetMapping("/statistics")
    public ResponseEntity<ApiResponse<HealthStatistics>> getStatistics(
            @RequestParam String userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        try {
            HealthStatistics statistics;
            if (date != null) {
                statistics = statisticsService.generateDailySummary(userId, date);
            } else {
                statistics = statisticsService.getTodayStatistics(userId);
            }
            return ResponseEntity.ok(ApiResponse.success(statistics));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(500, "查询统计数据失败: " + e.getMessage()));
        }
    }

    @GetMapping("/statistics/range")
    public ResponseEntity<ApiResponse<List<HealthStatistics>>> getStatisticsByRange(
            @RequestParam String userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        try {
            List<HealthStatistics> statistics = statisticsService.getStatisticsByDateRange(userId, startDate, endDate);
            return ResponseEntity.ok(ApiResponse.success(statistics));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(500, "查询统计数据失败: " + e.getMessage()));
        }
    }

    @GetMapping("/history")
    public ResponseEntity<ApiResponse<List<HealthHistory>>> getHistory(
            @RequestParam String userId,
            @RequestParam(required = false) String dataType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Integer limit) {
        try {
            List<HealthHistory> history;
            
            if (limit != null) {
                history = historyService.getRecentHistory(userId, limit);
            } else if (dataType != null && !dataType.isEmpty()) {
                history = historyService.getUserHistoryByType(userId, dataType);
            } else if (startDate != null && endDate != null) {
                history = historyService.getUserHistoryByDateRange(userId, startDate, endDate);
            } else {
                history = historyService.getUserHistory(userId);
            }
            
            return ResponseEntity.ok(ApiResponse.success(history));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(500, "查询历史记录失败: " + e.getMessage()));
        }
    }
}
