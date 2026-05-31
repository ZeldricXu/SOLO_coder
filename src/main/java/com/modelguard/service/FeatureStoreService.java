package com.modelguard.service;

import com.modelguard.common.PageResult;
import com.modelguard.dto.FeatureLookupDTO;
import com.modelguard.dto.FeatureRegisterDTO;
import com.modelguard.dto.FeatureValueDTO;
import com.modelguard.entity.FeatureRegistry;
import com.modelguard.entity.FeatureValue;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

public interface FeatureStoreService {

    Mono<FeatureRegistry> registerFeature(FeatureRegisterDTO dto);

    Mono<FeatureRegistry> getFeature(String featureId);

    Mono<FeatureRegistry> getFeatureVersion(String featureId, Integer version);

    Mono<PageResult<FeatureRegistry>> pageFeatures(String entity, String status, int pageNum, int pageSize);

    Mono<FeatureRegistry> updateFeature(String featureId, FeatureRegisterDTO dto);

    Mono<List<FeatureRegistry>> listEntityFeatures(String entity);

    Mono<FeatureValue> putFeatureValue(FeatureValueDTO dto);

    Mono<Boolean> batchPutFeatureValues(List<FeatureValueDTO> values);

    Mono<Map<String, Object>> getFeatureValues(FeatureLookupDTO dto);

    Mono<FeatureValue> getLatestFeatureValue(String featureId, String entityId);

    Mono<List<FeatureValue>> getFeatureValueHistory(String featureId, String entityId, LocalDateTime startTime, LocalDateTime endTime);

    Mono<Map<String, Object>> getOfflineFeatures(String entityId, List<String> featureIds, LocalDateTime asOfTime);

    Mono<Boolean> syncOfflineToOnline(String featureId, String entityId);

    Mono<Boolean> validateOnlineOfflineConsistency(String featureId, String entityId);

    Mono<Map<String, Object>> checkConsistency(String featureId);

    Mono<Void> deleteFeatureValues(String featureId, String entityId, LocalDateTime beforeTime);

    Mono<Map<String, Object>> getFeatureStats(String featureId);
}
