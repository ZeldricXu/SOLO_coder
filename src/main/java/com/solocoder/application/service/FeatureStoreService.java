package com.solocoder.application.service;

import com.solocoder.domain.model.ApiResponse;
import com.solocoder.domain.port.FeatureStorePort;
import com.solocoder.domain.port.StructuredLoggerPort;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestBody;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FeatureStoreService {

    private final FeatureStorePort featureStorePort;
    private final StructuredLoggerPort logger;

    public Mono<ApiResponse<Void>> registerFeature(String featureName, String description,
                                                    Map<String, Object> schema) {
        Map<String, Object> context = Map.of(
                "traceId", UUID.randomUUID().toString(),
                "featureName", featureName
        );
        logger.info("开始注册特征", context);

        return featureStorePort.registerFeature(featureName, description, schema)
                .then(Mono.just(ApiResponse.success(null)))
                .onErrorResume(e -> {
                    logger.error("特征注册失败", e, context);
                    return Mono.just(ApiResponse.error(422, e.getMessage()));
                });
    }

    public Mono<ApiResponse<Map<String, Object>>> getOnlineFeatures(String entityId, List<String> featureNames) {
        return featureStorePort.getOnlineFeatures(entityId, featureNames)
                .map(ApiResponse::success)
                .onErrorResume(e -> Mono.just(ApiResponse.error(500, e.getMessage())));
    }

    public Mono<ApiResponse<Flux<Map<String, Object>>>> getOfflineFeatures(String entityId,
                                                                            List<String> featureNames,
                                                                            Instant startTime, Instant endTime) {
        return Mono.just(ApiResponse.success(
                featureStorePort.getOfflineFeatures(entityId, featureNames, startTime, endTime)
        ));
    }

    public Mono<ApiResponse<Void>> ingestFeatures(String entityId, Map<String, Object> features, Instant eventTime) {
        return featureStorePort.ingestFeatures(entityId, features, eventTime)
                .then(Mono.just(ApiResponse.success(null)))
                .onErrorResume(e -> Mono.just(ApiResponse.error(500, e.getMessage())));
    }

    public Mono<ApiResponse<Boolean>> checkConsistency(String entityId, String featureName) {
        return featureStorePort.checkConsistency(entityId, featureName)
                .map(ApiResponse::success)
                .onErrorResume(e -> Mono.just(ApiResponse.error(500, e.getMessage())));
    }

    public Mono<ApiResponse<Void>> syncOnlineToOffline(String featureName) {
        return featureStorePort.syncOnlineToOffline(featureName)
                .then(Mono.just(ApiResponse.success(null)))
                .onErrorResume(e -> Mono.just(ApiResponse.error(500, e.getMessage())));
    }
}
