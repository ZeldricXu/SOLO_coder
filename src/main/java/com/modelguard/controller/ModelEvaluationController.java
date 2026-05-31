package com.modelguard.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.modelguard.common.ApiResponse;
import com.modelguard.common.PageResult;
import com.modelguard.dto.DriftDetectionDTO;
import com.modelguard.dto.EvaluationCreateDTO;
import com.modelguard.dto.MonitoringRecordDTO;
import com.modelguard.entity.DriftDetection;
import com.modelguard.entity.EvaluationMetric;
import com.modelguard.entity.OnlineMonitoring;
import com.modelguard.service.ModelEvaluationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/evaluation")
@RequiredArgsConstructor
public class ModelEvaluationController {

    private final ModelEvaluationService modelEvaluationService;

    @PostMapping("/evaluations")
    public Mono<ApiResponse<EvaluationMetric>> createEvaluation(@RequestBody EvaluationCreateDTO dto) {
        return modelEvaluationService.createEvaluation(dto)
                .map(ApiResponse::success);
    }

    @GetMapping("/evaluations/{evaluationId}")
    public Mono<ApiResponse<EvaluationMetric>> getEvaluation(@PathVariable String evaluationId) {
        return modelEvaluationService.getEvaluation(evaluationId)
                .map(ApiResponse::success);
    }

    @GetMapping("/evaluations")
    public Mono<ApiResponse<PageResult<EvaluationMetric>>> listEvaluations(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String modelId,
            @RequestParam(required = false) String version,
            @RequestParam(required = false) String evaluationType,
            @RequestParam(required = false) String status) {
        return modelEvaluationService.listEvaluations(page, size, modelId, version, evaluationType, status)
                .map(this::toPageResponse);
    }

    @PostMapping("/evaluations/compare")
    public Mono<ApiResponse<Map<String, Object>>> compareEvaluations(@RequestBody List<String> evaluationIds) {
        return modelEvaluationService.compareEvaluations(evaluationIds)
                .map(ApiResponse::success);
    }

    @GetMapping("/evaluations/compare-versions")
    public Mono<ApiResponse<Map<String, Object>>> compareModelVersions(
            @RequestParam String modelId,
            @RequestParam String version1,
            @RequestParam String version2,
            @RequestParam(required = false, defaultValue = "offline") String evaluationType) {
        return modelEvaluationService.compareModelVersions(modelId, version1, version2, evaluationType)
                .map(ApiResponse::success);
    }

    @DeleteMapping("/evaluations/{evaluationId}")
    public Mono<ApiResponse<Void>> deleteEvaluation(@PathVariable String evaluationId) {
        return modelEvaluationService.deleteEvaluation(evaluationId)
                .then(Mono.just(ApiResponse.success()));
    }

    @PostMapping("/monitoring")
    public Mono<ApiResponse<OnlineMonitoring>> recordMonitoringData(@RequestBody MonitoringRecordDTO dto) {
        return modelEvaluationService.recordMonitoringData(dto)
                .map(ApiResponse::success);
    }

    @GetMapping("/monitoring/{modelId}/{version}/latest")
    public Mono<ApiResponse<OnlineMonitoring>> getLatestMonitoring(
            @PathVariable String modelId,
            @PathVariable String version) {
        return modelEvaluationService.getLatestMonitoring(modelId, version)
                .map(ApiResponse::success);
    }

    @GetMapping("/monitoring/{modelId}/{version}/history")
    public Mono<ApiResponse<List<OnlineMonitoring>>> getMonitoringHistory(
            @PathVariable String modelId,
            @PathVariable String version,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @RequestParam(required = false) String timeWindow) {
        return modelEvaluationService.getMonitoringHistory(modelId, version, startTime, endTime, timeWindow)
                .map(ApiResponse::success);
    }

    @GetMapping("/monitoring/{modelId}/{version}/summary")
    public Mono<ApiResponse<Map<String, Object>>> getMonitoringSummary(
            @PathVariable String modelId,
            @PathVariable String version,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        LocalDateTime actualStart = startTime != null ? startTime : LocalDateTime.now().minusDays(7);
        LocalDateTime actualEnd = endTime != null ? endTime : LocalDateTime.now();
        return modelEvaluationService.getMonitoringSummary(modelId, version, actualStart, actualEnd)
                .map(ApiResponse::success);
    }

    @PostMapping("/drift/detect")
    public Mono<ApiResponse<DriftDetection>> detectDrift(@RequestBody DriftDetectionDTO dto) {
        return modelEvaluationService.detectDrift(dto)
                .map(ApiResponse::success);
    }

    @PostMapping("/drift/detect-all")
    public Mono<ApiResponse<List<DriftDetection>>> detectAllDrifts(@RequestBody DriftDetectionDTO dto) {
        return modelEvaluationService.detectAllDrifts(dto)
                .map(ApiResponse::success);
    }

    @GetMapping("/drift/{detectionId}")
    public Mono<ApiResponse<DriftDetection>> getDriftDetection(@PathVariable String detectionId) {
        return modelEvaluationService.getDriftDetection(detectionId)
                .map(ApiResponse::success);
    }

    @GetMapping("/drift")
    public Mono<ApiResponse<PageResult<DriftDetection>>> listDriftDetections(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String modelId,
            @RequestParam(required = false) String version,
            @RequestParam(required = false) String driftType,
            @RequestParam(required = false) String driftStatus,
            @RequestParam(required = false) String severity) {
        return modelEvaluationService.listDriftDetections(page, size, modelId, version, driftType, driftStatus, severity)
                .map(this::toPageResponse);
    }

    @GetMapping("/drift/{modelId}/{version}/summary")
    public Mono<ApiResponse<Map<String, Object>>> getDriftSummary(
            @PathVariable String modelId,
            @PathVariable String version,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        LocalDateTime actualStart = startTime != null ? startTime : LocalDateTime.now().minusDays(7);
        LocalDateTime actualEnd = endTime != null ? endTime : LocalDateTime.now();
        return modelEvaluationService.getDriftSummary(modelId, version, actualStart, actualEnd)
                .map(ApiResponse::success);
    }

    @PostMapping("/drift/scheduled")
    public Mono<ApiResponse<Void>> triggerScheduledDriftDetection() {
        return modelEvaluationService.scheduledDriftDetection()
                .collectList()
                .then(Mono.just(ApiResponse.success()));
    }

    @GetMapping("/dashboard/model/{modelId}/{version}")
    public Mono<ApiResponse<Map<String, Object>>> getModelDashboard(
            @PathVariable String modelId,
            @PathVariable String version) {
        return modelEvaluationService.getModelDashboard(modelId, version)
                .map(ApiResponse::success);
    }

    @GetMapping("/dashboard/overall")
    public Mono<ApiResponse<Map<String, Object>>> getOverallDashboard(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return modelEvaluationService.getOverallDashboard(startTime, endTime)
                .map(ApiResponse::success);
    }

    @PostMapping("/statistics/psi")
    public Mono<ApiResponse<Double>> calculatePSI(
            @RequestBody Map<String, Object> request) {
        Map<String, Object> expected = (Map<String, Object>) request.get("expected");
        Map<String, Object> actual = (Map<String, Object>) request.get("actual");
        return Mono.just(ApiResponse.success(modelEvaluationService.calculatePSI(expected, actual)));
    }

    @PostMapping("/statistics/ks")
    public Mono<ApiResponse<Double>> calculateKSStatistic(
            @RequestBody Map<String, Object> request) {
        Map<String, Object> dist1 = (Map<String, Object>) request.get("distribution1");
        Map<String, Object> dist2 = (Map<String, Object>) request.get("distribution2");
        return Mono.just(ApiResponse.success(modelEvaluationService.calculateKSStatistic(dist1, dist2)));
    }

    @PostMapping("/statistics/wasserstein")
    public Mono<ApiResponse<Double>> calculateWassersteinDistance(
            @RequestBody Map<String, Object> request) {
        Map<String, Object> dist1 = (Map<String, Object>) request.get("distribution1");
        Map<String, Object> dist2 = (Map<String, Object>) request.get("distribution2");
        return Mono.just(ApiResponse.success(modelEvaluationService.calculateWassersteinDistance(dist1, dist2)));
    }

    private <T> ApiResponse<PageResult<T>> toPageResponse(Page<T> page) {
        PageResult<T> result = new PageResult<>();
        result.setTotal(page.getTotal());
        result.setPage((int) page.getCurrent());
        result.setSize((int) page.getSize());
        result.setRecords(page.getRecords());
        return ApiResponse.success(result);
    }
}
