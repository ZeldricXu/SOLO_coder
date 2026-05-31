package com.solocoder.platform.featurestore.service.impl;

import com.solocoder.platform.featurestore.model.FeatureValue;
import com.solocoder.platform.featurestore.service.OnlineFeatureService;
import com.solocoder.platform.common.util.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class OnlineFeatureServiceImpl implements OnlineFeatureService {

    private static final String KEY_PATTERN = "feature:%s:entity:%s";
    private static final long DEFAULT_TTL_HOURS = 24;

    private final StringRedisTemplate redisTemplate;

    @Override
    public void put(FeatureValue featureValue) {
        String key = buildKey(featureValue.getFeatureId(), featureValue.getEntityId());
        String value = JsonUtils.toJson(featureValue);
        redisTemplate.opsForValue().set(key, value, DEFAULT_TTL_HOURS, TimeUnit.HOURS);
        log.debug("Online feature stored: feature={}, entity={}", featureValue.getFeatureId(), featureValue.getEntityId());
    }

    @Override
    public Optional<FeatureValue> get(String featureId, String entityId) {
        String key = buildKey(featureId, entityId);
        String value = redisTemplate.opsForValue().get(key);
        if (value != null) {
            FeatureValue fv = JsonUtils.fromJson(value, FeatureValue.class);
            return Optional.of(fv);
        }
        return Optional.empty();
    }

    @Override
    public Map<String, FeatureValue> getBatch(String entityId, List<String> featureIds) {
        Map<String, FeatureValue> result = new HashMap<>();
        for (String featureId : featureIds) {
            get(featureId, entityId).ifPresent(fv -> result.put(featureId, fv));
        }
        return result;
    }

    @Override
    public Map<String, Map<String, FeatureValue>> getMultiEntityBatch(List<String> entityIds, List<String> featureIds) {
        Map<String, Map<String, FeatureValue>> result = new HashMap<>();
        for (String entityId : entityIds) {
            result.put(entityId, getBatch(entityId, featureIds));
        }
        return result;
    }

    @Override
    public void delete(String featureId, String entityId) {
        String key = buildKey(featureId, entityId);
        redisTemplate.delete(key);
        log.debug("Online feature deleted: feature={}, entity={}", featureId, entityId);
    }

    private String buildKey(String featureId, String entityId) {
        return String.format(KEY_PATTERN, featureId, entityId);
    }
}
