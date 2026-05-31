package com.solocoder.platform.featurestore.service.impl;

import com.solocoder.platform.featurestore.model.FeatureValue;
import com.solocoder.platform.featurestore.service.OfflineFeatureService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class OfflineFeatureServiceImpl implements OfflineFeatureService {

    private final Map<String, List<FeatureValue>> historicalStore = new ConcurrentHashMap<>();

    @Override
    public void store(FeatureValue featureValue) {
        String key = featureValue.getFeatureId() + ":" + featureValue.getEntityId();
        historicalStore.computeIfAbsent(key, k -> new ArrayList<>()).add(featureValue);
        log.debug("Offline feature stored: feature={}, entity={}, timestamp={}",
                featureValue.getFeatureId(), featureValue.getEntityId(), featureValue.getTimestamp());
    }

    @Override
    public Optional<FeatureValue> query(String featureId, String entityId, long timestamp) {
        String key = featureId + ":" + entityId;
        List<FeatureValue> history = historicalStore.get(key);
        if (history == null || history.isEmpty()) return Optional.empty();

        return history.stream()
                .filter(fv -> fv.getTimestamp() <= timestamp)
                .max(Comparator.comparingLong(FeatureValue::getTimestamp));
    }

    @Override
    public List<FeatureValue> queryRange(String featureId, String entityId, long startTimestamp, long endTimestamp) {
        String key = featureId + ":" + entityId;
        List<FeatureValue> history = historicalStore.getOrDefault(key, List.of());
        return history.stream()
                .filter(fv -> fv.getTimestamp() >= startTimestamp && fv.getTimestamp() <= endTimestamp)
                .sorted(Comparator.comparingLong(FeatureValue::getTimestamp))
                .collect(Collectors.toList());
    }

    @Override
    public List<FeatureValue> backtrack(String featureId, String entityId, long targetTimestamp) {
        String key = featureId + ":" + entityId;
        List<FeatureValue> history = historicalStore.getOrDefault(key, List.of());
        return history.stream()
                .filter(fv -> fv.getTimestamp() <= targetTimestamp)
                .sorted(Comparator.comparingLong(FeatureValue::getTimestamp).reversed())
                .collect(Collectors.toList());
    }
}
