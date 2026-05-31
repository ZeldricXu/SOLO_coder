package com.modelguard.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.modelguard.dto.DriftDetectionDTO;
import com.modelguard.dto.EvaluationCreateDTO;
import com.modelguard.dto.MonitoringRecordDTO;
import com.modelguard.entity.DriftDetection;
import com.modelguard.entity.EvaluationMetric;
import com.modelguard.entity.OnlineMonitoring;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public interface ModelEvaluationService {

    Mono<EvaluationMetric> createEvaluation(EvaluationCreateDTO dto);

    Mono<EvaluationMetric> getEvaluation(String evaluationId);

    Mono<Page<EvaluationMetric>> listEvaluations(int page, int size, String modelId, String version,
                                                  String evaluationType, String status);

    Mono<Map<String, Object>> compareEvaluations(List<String> evaluationIds);

    Mono<Map<String, Object>> compareModelVersions(String modelId, String version1, String version2, String evaluationType);

    Mono<Void> deleteEvaluation(String evaluationId);

    Mono<OnlineMonitoring> recordMonitoringData(MonitoringRecordDTO dto);

    Mono<OnlineMonitoring> getLatestMonitoring(String modelId, String version);

    Mono<List<OnlineMonitoring>> getMonitoringHistory(String modelId, String version,
                                                       LocalDateTime startTime, LocalDateTime endTime,
                                                       String timeWindow);

    Mono<Map<String, Object>> getMonitoringSummary(String modelId, String version,
                                                    LocalDateTime startTime, LocalDateTime endTime);

    Mono<DriftDetection> detectDrift(DriftDetectionDTO dto);

    Mono<DriftDetection> getDriftDetection(String detectionId);

    Mono<Page<DriftDetection>> listDriftDetections(int page, int size, String modelId, String version,
                                                    String driftType, String driftStatus, String severity);

    Mono<List<DriftDetection>> detectAllDrifts(DriftDetectionDTO dto);

    Mono<Map<String, Object>> getDriftSummary(String modelId, String version,
                                               LocalDateTime startTime, LocalDateTime endTime);

    Flux<DriftDetection> scheduledDriftDetection();

    Flux<OnlineMonitoring> aggregateMonitoringData();

    Mono<Map<String, Object>> getModelDashboard(String modelId, String version);

    Mono<Map<String, Object>> getOverallDashboard(LocalDateTime startTime, LocalDateTime endTime);

    double calculateKSStatistic(Map<String, Object> dist1, Map<String, Object> dist2);

    double calculateWassersteinDistance(Map<String, Object> dist1, Map<String, Object> dist2);

    double calculatePSI(Map<String, Object> expected, Map<String, Object> actual);
}
