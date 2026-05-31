package com.modelguard.service.prompt;

import com.modelguard.dto.request.ExperimentResultRecordRequest;
import com.modelguard.dto.response.AbExperimentResultResponse;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

public interface AbExperimentResultService {

    Mono<AbExperimentResultResponse> recordResult(ExperimentResultRecordRequest request);

    Mono<List<AbExperimentResultResponse>> getResultsByExperiment(String experimentId);

    Mono<List<AbExperimentResultResponse>> getResultsByGroup(String experimentId, String groupId);

    Mono<Long> countResultsByGroup(String experimentId, String groupId);

    Mono<Map<String, Object> > calculateGroupMetrics(String experimentId, String groupId);

    Mono<Double> calculateMetricAverage(String experimentId, String groupId, String metricName);

    Mono<Map<String, Long>> getResultCountsByGroup(String experimentId);
}
