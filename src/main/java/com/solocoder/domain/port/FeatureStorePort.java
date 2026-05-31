package com.solocoder.domain.port;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface FeatureStorePort {

    Mono<Void> registerFeature(String featureName, String description, Map<String, Object> schema);

    Mono<Map<String, Object>> getOnlineFeatures(String entityId, List<String> featureNames);

    Flux<Map<String, Object>> getOfflineFeatures(String entityId, List<String> featureNames,
                                                  Instant startTime, Instant endTime);

    Mono<Void> ingestFeatures(String entityId, Map<String, Object> features, Instant eventTime);

    Mono<Void> syncOnlineToOffline(String featureName);

    Mono<Boolean> checkConsistency(String entityId, String featureName);
}
