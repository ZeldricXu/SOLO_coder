package com.healthtrack.controller;

import com.healthtrack.dto.ApiResponse;
import com.healthtrack.dto.HealthAdviceResponse;
import com.healthtrack.dto.HealthDataReportRequest;
import com.healthtrack.dto.HealthDataReportResponse;
import com.healthtrack.dto.HealthIndicatorsResponse;
import com.healthtrack.service.AdvicePushService;
import com.healthtrack.service.DataCollectionService;
import com.healthtrack.service.IndicatorTrackingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/health")
public class HealthApiController {

    @Autowired
    private DataCollectionService dataCollectionService;

    @Autowired
    private IndicatorTrackingService indicatorTrackingService;

    @Autowired
    private AdvicePushService advicePushService;

    @PostMapping("/report")
    public ResponseEntity<ApiResponse<HealthDataReportResponse>> reportHealthData(@RequestBody HealthDataReportRequest request) {
        try {
            HealthDataReportResponse response = dataCollectionService.reportHealthData(request);
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(500, "数据上报失败: " + e.getMessage()));
        }
    }

    @GetMapping("/indicators")
    public ResponseEntity<ApiResponse<HealthIndicatorsResponse>> getIndicators(
            @RequestParam String userId,
            @RequestParam(required = false) String indicatorType) {
        try {
            List<HealthIndicatorsResponse.IndicatorInfo> indicators;
            
            if (indicatorType != null && !indicatorType.isEmpty()) {
                indicators = indicatorTrackingService.getUserIndicatorByType(userId, indicatorType)
                        .map(ind -> List.of(new HealthIndicatorsResponse.IndicatorInfo(
                                ind.getIndicatorType(),
                                ind.getCurrentValue(),
                                ind.getAverageValue(),
                                ind.getTrend(),
                                ind.getStatus()
                        )))
                        .orElse(List.of());
            } else {
                indicators = indicatorTrackingService.getUserIndicators(userId).stream()
                        .map(ind -> new HealthIndicatorsResponse.IndicatorInfo(
                                ind.getIndicatorType(),
                                ind.getCurrentValue(),
                                ind.getAverageValue(),
                                ind.getTrend(),
                                ind.getStatus()
                        ))
                        .collect(Collectors.toList());
            }
            
            return ResponseEntity.ok(ApiResponse.success(new HealthIndicatorsResponse(indicators)));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(500, "查询指标失败: " + e.getMessage()));
        }
    }

    @GetMapping("/advice")
    public ResponseEntity<ApiResponse<HealthAdviceResponse>> getAdvice(@RequestParam String userId) {
        try {
            List<HealthAdviceResponse.AdviceInfo> advices = advicePushService.getUserAdvices(userId).stream()
                    .map(advice -> new HealthAdviceResponse.AdviceInfo(
                            advice.getAdviceId(),
                            advice.getAdviceType(),
                            advice.getAdviceContent(),
                            advice.getPriority(),
                            advice.getGeneratedAt(),
                            advice.getReadStatus()
                    ))
                    .collect(Collectors.toList());
            
            return ResponseEntity.ok(ApiResponse.success(new HealthAdviceResponse(advices)));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(500, "查询建议失败: " + e.getMessage()));
        }
    }

    @PostMapping("/advice/{adviceId}/read")
    public ResponseEntity<ApiResponse<Void>> markAdviceAsRead(@PathVariable String adviceId) {
        try {
            advicePushService.markAdviceAsRead(adviceId);
            return ResponseEntity.ok(ApiResponse.success(null));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(500, "标记已读失败: " + e.getMessage()));
        }
    }
}
