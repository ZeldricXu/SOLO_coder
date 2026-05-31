package com.solocoder.platform.featurestore.service;

import com.solocoder.platform.featurestore.model.FeatureValue;

import java.util.List;
import java.util.Optional;

public interface OfflineFeatureService {

    void store(FeatureValue featureValue);

    Optional<FeatureValue> query(String featureId, String entityId, long timestamp);

    List<FeatureValue> queryRange(String featureId, String entityId, long startTimestamp, long endTimestamp);

    List<FeatureValue> backtrack(String featureId, String entityId, long targetTimestamp);
}
