package com.solocoder.infrastructure.adapter.featurestore;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solocoder.domain.port.FeatureStorePort;
import com.solocoder.infrastructure.adapter.featurestore.event.FeatureEventPublisher;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RMap;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class RedisFeatureStoreAdapter implements FeatureStorePort {

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final FeatureEventPublisher eventPublisher;

    private static final String ONLINE_FEATURE_PREFIX = "feature:online:";
    private static final String OFFLINE_FEATURE_PREFIX = "feature:offline:";
    private static final String FEATURE_REGISTRY_PREFIX = "feature:registry:";
    private static final String FEATURE_EVENT_PREFIX = "feature:event:";

    @Override
    public Mono<Void> registerFeature(String featureName, String description, Map<String, Object> schema) {
        return Mono.fromRunnable(() -> {
            RMap<String, Object> registry = redissonClient.getMap(FEATURE_REGISTRY_PREFIX + featureName);
            registry.put("description", description);
            registry.put("schema", serialize(schema));
            registry.put("createdAt", Instant.now().toString());
            registry.put("status", "active");
            eventPublisher.publishFeatureRegistered(featureName, description, schema);
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public Mono<Map<String, Object>> getOnlineFeatures(String entityId, List<String> featureNames) {
        return Mono.fromCallable(() -> {
            Map<String, Object> features = new HashMap<>();
            for (String featureName : featureNames) {
                RMapCache<String, Object> onlineStore = redissonClient.getMapCache(
                        ONLINE_FEATURE_PREFIX + featureName);
                Object value = onlineStore.get(entityId);
                if (value != null) {
                    features.put(featureName, deserialize(value.toString()));
                }
            }
            return features;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Flux<Map<String, Object>> getOfflineFeatures(String entityId, List<String> featureNames,
                                                         Instant startTime, Instant endTime) {
        return Flux.fromIterable(featureNames)
                .flatMap(featureName -> Mono.fromCallable(() -> {
                    RMap<String, String> offlineStore = redissonClient.getMap(
                            OFFLINE_FEATURE_PREFIX + featureName + ":" + entityId);
                    Map<String, Object> result = new HashMap<>();
                    offlineStore.entrySet().stream()
                            .filter(entry -> {
                                Instant eventTime = Instant.parse(entry.getKey());
                                return eventTime.isAfter(startTime) && eventTime.isBefore(endTime);
                            })
                            .forEach(entry -> result.put(entry.getKey(), deserialize(entry.getValue())));
                    return result;
                }))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> ingestFeatures(String entityId, Map<String, Object> features, Instant eventTime) {
        return Mono.fromRunnable(() -> {
            String eventTimeStr = eventTime.toString();
            for (Map.Entry<String, Object> entry : features.entrySet()) {
                String featureName = entry.getKey();
                Object featureValue = entry.getValue();
                String serializedValue = serialize(featureValue);

                RMapCache<String, Object> onlineStore = redissonClient.getMapCache(
                        ONLINE_FEATURE_PREFIX + featureName);
                onlineStore.put(entityId, serializedValue, 24, TimeUnit.HOURS);

                RMap<String, String> offlineStore = redissonClient.getMap(
                        OFFLINE_FEATURE_PREFIX + featureName + ":" + entityId);
                offlineStore.put(eventTimeStr, serializedValue);

                RMap<String, String> eventStore = redissonClient.getMap(
                        FEATURE_EVENT_PREFIX + featureName);
                eventStore.put(entityId + ":" + eventTimeStr, serializedValue);

                eventPublisher.publishFeatureIngested(entityId, featureName, featureValue);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public Mono<Void> syncOnlineToOffline(String featureName) {
        return Mono.fromRunnable(() -> {
            RMapCache<String, Object> onlineStore = redissonClient.getMapCache(
                    ONLINE_FEATURE_PREFIX + featureName);
            onlineStore.forEach((entityId, value) -> {
                RMap<String, String> offlineStore = redissonClient.getMap(
                        OFFLINE_FEATURE_PREFIX + featureName + ":" + entityId);
                offlineStore.put(Instant.now().toString(), value.toString());
            });
            eventPublisher.publishFeatureSynced(featureName);
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public Mono<Boolean> checkConsistency(String entityId, String featureName) {
        return Mono.fromCallable(() -> {
            RMapCache<String, Object> onlineStore = redissonClient.getMapCache(
                    ONLINE_FEATURE_PREFIX + featureName);
            Object onlineValue = onlineStore.get(entityId);

            if (onlineValue == null) {
                return true;
            }

            RMap<String, String> offlineStore = redissonClient.getMap(
                    OFFLINE_FEATURE_PREFIX + featureName + ":" + entityId);
            boolean consistent = offlineStore.containsValue(onlineValue.toString());

            if (!consistent) {
                eventPublisher.publishConsistencyCheckFailed(entityId, featureName);
            }

            return consistent;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return value.toString();
        }
    }

    private Object deserialize(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<Object>() {});
        } catch (Exception e) {
            return value;
        }
    }
}
