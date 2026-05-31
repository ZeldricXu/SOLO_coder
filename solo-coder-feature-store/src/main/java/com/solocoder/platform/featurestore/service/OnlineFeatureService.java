package com.solocoder.platform.featurestore.service;

import com.solocoder.platform.featurestore.model.FeatureValue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface OnlineFeatureService {

    void put(FeatureValue featureValue);

    Optional<FeatureValue> get(String featureId, String entityId);

    Map<String, FeatureValue> getBatch(String entityId, List<String> featureIds);

    Map<String, Map<String, FeatureValue>> getMultiEntityBatch(List<String> entityIds, List<String> featureIds);

    void delete(String featureId, String entityId);
}
