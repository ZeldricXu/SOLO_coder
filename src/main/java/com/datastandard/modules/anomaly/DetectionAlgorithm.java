package com.datastandard.modules.anomaly;

import com.datastandard.modules.anomaly.dto.AlgorithmConfig;
import com.datastandard.modules.anomaly.dto.AnomalyDetectionRequest;
import com.datastandard.modules.anomaly.dto.AnomalyResult;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;

public interface DetectionAlgorithm {

    String getAlgorithmName();

    String getAlgorithmType();

    Mono<List<AnomalyResult>> detect(AnomalyDetectionRequest request, AlgorithmConfig config);

    Mono<BigDecimal> calculateAnomalyScore(List<BigDecimal> data, BigDecimal value, AlgorithmConfig config);

    default Mono<Boolean> isDataSufficient(List<AnomalyDetectionRequest.DataPoint> dataPoints, AlgorithmConfig config) {
        int minPoints = config.getMinDataPoints() != null ? config.getMinDataPoints() : 5;
        return Mono.just(dataPoints.size() >= minPoints);
    }

    default Flux<AnomalyResult> detectMultiDimension(AnomalyDetectionRequest request, AlgorithmConfig config) {
        return Mono.just(request)
                .flatMapMany(req -> {
                    if (req.getDimensions() == null || req.getDimensions().isEmpty()) {
                        return detect(req, config).flatMapMany(Flux::fromIterable);
                    }
                    return Flux.fromIterable(req.getDimensions())
                            .flatMap(dimension -> {
                                AnomalyDetectionRequest dimensionRequest = AnomalyDetectionRequest.builder()
                                        .detectionCode(req.getDetectionCode() + "_" + dimension)
                                        .metricCode(req.getMetricCode())
                                        .entityId(req.getEntityId())
                                        .instanceId(req.getInstanceId())
                                        .dataPoints(req.getDataPoints())
                                        .algorithmConfig(config)
                                        .dimensions(List.of(dimension))
                                        .tags(req.getTags())
                                        .severityLevel(req.getSeverityLevel())
                                        .windowStart(req.getWindowStart())
                                        .windowEnd(req.getWindowEnd())
                                        .build();
                                return detect(dimensionRequest, config)
                                        .flatMapMany(Flux::fromIterable)
                                        .map(result -> {
                                            result.setAffectedDimensions(List.of(dimension));
                                            return result;
                                        });
                            });
                });
    }
}
