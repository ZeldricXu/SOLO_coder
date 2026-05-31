package com.modelguard.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.modelguard.common.PageResult;
import com.modelguard.dto.FeatureLookupDTO;
import com.modelguard.dto.FeatureRegisterDTO;
import com.modelguard.dto.FeatureValueDTO;
import com.modelguard.entity.FeatureRegistry;
import com.modelguard.entity.FeatureValue;
import com.modelguard.exception.BusinessException;
import com.modelguard.exception.ResourceNotFoundException;
import com.modelguard.mapper.FeatureRegistryMapper;
import com.modelguard.mapper.FeatureValueMapper;
import com.modelguard.service.FeatureStoreService;
import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeatureStoreServiceImpl implements FeatureStoreService {

    private final FeatureRegistryMapper featureRegistryMapper;
    private final FeatureValueMapper featureValueMapper;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String FEATURE_ONLINE_KEY_PREFIX = "feature:online:";

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<FeatureRegistry> registerFeature(FeatureRegisterDTO dto) {
        return Mono.fromCallable(() -> {
            String featureId = dto.getFeatureId() != null ? dto.getFeatureId() : "feat_" + IdUtil.simpleUUID();

            LambdaQueryWrapper<FeatureRegistry> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(FeatureRegistry::getFeatureId, featureId)
                    .orderByDesc(FeatureRegistry::getVersion)
                    .last("LIMIT 1");
            FeatureRegistry latest = featureRegistryMapper.selectOne(wrapper);

            int newVersion = latest != null ? latest.getVersion() + 1 : 1;

            FeatureRegistry feature = new FeatureRegistry();
            feature.setFeatureId(featureId);
            feature.setName(dto.getName());
            feature.setDescription(dto.getDescription());
            feature.setVersion(newVersion);
            feature.setDataType(dto.getDataType());
            feature.setFeatureType(dto.getFeatureType());
            feature.setEntity(dto.getEntity());
            feature.setSource(dto.getSource());
            feature.setTtlSeconds(dto.getTtlSeconds());
            feature.setSchemaDef(dto.getSchemaDef());
            feature.setStatus("ACTIVE");
            feature.setCreatedBy(dto.getCreatedBy());

            featureRegistryMapper.insert(feature);
            log.info("Registered feature: featureId={}, version={}", featureId, newVersion);
            return feature;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<FeatureRegistry> getFeature(String featureId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<FeatureRegistry> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(FeatureRegistry::getFeatureId, featureId)
                    .orderByDesc(FeatureRegistry::getVersion)
                    .last("LIMIT 1");
            FeatureRegistry feature = featureRegistryMapper.selectOne(wrapper);
            if (feature == null) {
                throw new ResourceNotFoundException("FeatureRegistry", featureId);
            }
            return feature;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<FeatureRegistry> getFeatureVersion(String featureId, Integer version) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<FeatureRegistry> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(FeatureRegistry::getFeatureId, featureId)
                    .eq(FeatureRegistry::getVersion, version);
            FeatureRegistry feature = featureRegistryMapper.selectOne(wrapper);
            if (feature == null) {
                throw new ResourceNotFoundException("FeatureRegistry", featureId + " v" + version);
            }
            return feature;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<PageResult<FeatureRegistry>> pageFeatures(String entity, String status, int pageNum, int pageSize) {
        return Mono.fromCallable(() -> {
            Page<FeatureRegistry> page = new Page<>(pageNum, pageSize);
            LambdaQueryWrapper<FeatureRegistry> wrapper = new LambdaQueryWrapper<>();
            if (entity != null && !entity.isEmpty()) {
                wrapper.eq(FeatureRegistry::getEntity, entity);
            }
            if (status != null && !status.isEmpty()) {
                wrapper.eq(FeatureRegistry::getStatus, status);
            }
            wrapper.groupBy(FeatureRegistry::getFeatureId)
                    .orderByDesc(FeatureRegistry::getCreatedAt);
            Page<FeatureRegistry> result = featureRegistryMapper.selectPage(page, wrapper);
            return PageResult.of(result.getRecords(), result.getTotal(), pageNum, pageSize);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<FeatureRegistry> updateFeature(String featureId, FeatureRegisterDTO dto) {
        return registerFeature(dto);
    }

    @Override
    public Mono<List<FeatureRegistry>> listEntityFeatures(String entity) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<FeatureRegistry> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(FeatureRegistry::getEntity, entity)
                    .eq(FeatureRegistry::getStatus, "ACTIVE")
                    .groupBy(FeatureRegistry::getFeatureId)
                    .orderByDesc(FeatureRegistry::getVersion);
            return featureRegistryMapper.selectList(wrapper);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<FeatureValue> putFeatureValue(FeatureValueDTO dto) {
        return getFeature(dto.getFeatureId())
                .flatMap(feature -> Mono.fromCallable(() -> {
                    LocalDateTime timestamp = dto.getTimestamp() != null ? dto.getTimestamp() : LocalDateTime.now();

                    FeatureValue value = new FeatureValue();
                    value.setFeatureId(dto.getFeatureId());
                    value.setEntityId(dto.getEntityId());
                    value.setValue(dto.getValue());
                    value.setTimestamp(timestamp);
                    value.setIsOnline(dto.getIsOnline());

                    featureValueMapper.insert(value);

                    if (Boolean.TRUE.equals(dto.getIsOnline())) {
                        String key = FEATURE_ONLINE_KEY_PREFIX + dto.getFeatureId() + ":" + dto.getEntityId();
                        redisTemplate.opsForValue().set(key, dto.getValue(),
                                feature.getTtlSeconds() != null ? feature.getTtlSeconds() : 86400L,
                                TimeUnit.SECONDS).subscribe();
                    }

                    log.debug("Put feature value: featureId={}, entityId={}, online={}",
                            dto.getFeatureId(), dto.getEntityId(), dto.getIsOnline());
                    return value;
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<Boolean> batchPutFeatureValues(List<FeatureValueDTO> values) {
        return Flux.fromIterable(values)
                .flatMap(this::putFeatureValue, 10)
                .collectList()
                .map(results -> results.size() == values.size());
    }

    @Override
    public Mono<Map<String, Object>> getFeatureValues(FeatureLookupDTO dto) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entityId", dto.getEntityId());
        result.put("asOfTime", dto.getAsOfTime() != null ? dto.getAsOfTime() : LocalDateTime.now());

        Map<String, Mono<String>> onlineMonoMap = new HashMap<>();
        if (Boolean.TRUE.equals(dto.getOnlineOnly())) {
            for (String featureId : dto.getFeatureIds()) {
                String key = FEATURE_ONLINE_KEY_PREFIX + featureId + ":" + dto.getEntityId();
                onlineMonoMap.put(featureId, redisTemplate.opsForValue().get(key));
            }
        }

        return Mono.zip(onlineMonoMap.values().stream().collect(Collectors.toList()), objects -> {
            Map<String, Object> values = new LinkedHashMap<>();
            int idx = 0;
            for (String featureId : dto.getFeatureIds()) {
                if (Boolean.TRUE.equals(dto.getOnlineOnly())) {
                    values.put(featureId, objects[idx++]);
                } else {
                    values.put(featureId, getLatestValueFromDb(featureId, dto.getEntityId(), dto.getAsOfTime()));
                }
            }
            result.put("values", values);
            result.put("source", Boolean.TRUE.equals(dto.getOnlineOnly()) ? "ONLINE" : "OFFLINE");
            return result;
        });
    }

    private String getLatestValueFromDb(String featureId, String entityId, LocalDateTime asOfTime) {
        LambdaQueryWrapper<FeatureValue> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FeatureValue::getFeatureId, featureId)
                .eq(FeatureValue::getEntityId, entityId);
        if (asOfTime != null) {
            wrapper.le(FeatureValue::getTimestamp, asOfTime);
        }
        wrapper.orderByDesc(FeatureValue::getTimestamp)
                .last("LIMIT 1");
        FeatureValue value = featureValueMapper.selectOne(wrapper);
        return value != null ? value.getValue() : null;
    }

    @Override
    public Mono<FeatureValue> getLatestFeatureValue(String featureId, String entityId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<FeatureValue> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(FeatureValue::getFeatureId, featureId)
                    .eq(FeatureValue::getEntityId, entityId)
                    .eq(FeatureValue::getIsOnline, true)
                    .orderByDesc(FeatureValue::getTimestamp)
                    .last("LIMIT 1");
            FeatureValue value = featureValueMapper.selectOne(wrapper);
            if (value == null) {
                throw new ResourceNotFoundException("FeatureValue", featureId + ":" + entityId);
            }
            return value;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<List<FeatureValue>> getFeatureValueHistory(String featureId, String entityId, LocalDateTime startTime, LocalDateTime endTime) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<FeatureValue> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(FeatureValue::getFeatureId, featureId)
                    .eq(FeatureValue::getEntityId, entityId);
            if (startTime != null) {
                wrapper.ge(FeatureValue::getTimestamp, startTime);
            }
            if (endTime != null) {
                wrapper.le(FeatureValue::getTimestamp, endTime);
            }
            wrapper.orderByAsc(FeatureValue::getTimestamp);
            return featureValueMapper.selectList(wrapper);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Map<String, Object>> getOfflineFeatures(String entityId, List<String> featureIds, LocalDateTime asOfTime) {
        return Mono.fromCallable(() -> {
            Map<String, Object> values = new LinkedHashMap<>();
            for (String featureId : featureIds) {
                String value = getLatestValueFromDb(featureId, entityId, asOfTime);
                values.put(featureId, value);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("entityId", entityId);
            result.put("asOfTime", asOfTime != null ? asOfTime : LocalDateTime.now());
            result.put("values", values);
            result.put("source", "OFFLINE");
            return result;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<Boolean> syncOfflineToOnline(String featureId, String entityId) {
        return Mono.fromCallable(() -> {
            String offlineValue = getLatestValueFromDb(featureId, entityId, null);
            if (offlineValue == null) {
                return false;
            }

            String key = FEATURE_ONLINE_KEY_PREFIX + featureId + ":" + entityId;
            FeatureRegistry feature = getFeature(featureId).block();
            long ttl = feature != null && feature.getTtlSeconds() != null ? feature.getTtlSeconds() : 86400L;
            redisTemplate.opsForValue().set(key, offlineValue, ttl, TimeUnit.SECONDS).subscribe();

            FeatureValue value = new FeatureValue();
            value.setFeatureId(featureId);
            value.setEntityId(entityId);
            value.setValue(offlineValue);
            value.setTimestamp(LocalDateTime.now());
            value.setIsOnline(true);
            featureValueMapper.insert(value);

            log.info("Synced feature offline to online: featureId={}, entityId={}", featureId, entityId);
            return true;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Boolean> validateOnlineOfflineConsistency(String featureId, String entityId) {
        return Mono.fromCallable(() -> {
            String onlineKey = FEATURE_ONLINE_KEY_PREFIX + featureId + ":" + entityId;
            String onlineValue = redisTemplate.opsForValue().get(onlineKey).block();
            String offlineValue = getLatestValueFromDb(featureId, entityId, null);

            boolean consistent = Objects.equals(onlineValue, offlineValue);
            log.info("Feature consistency check: featureId={}, entityId={}, consistent={}",
                    featureId, entityId, consistent);
            return consistent;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Map<String, Object>> checkConsistency(String featureId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<FeatureValue> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(FeatureValue::getFeatureId, featureId)
                    .eq(FeatureValue::getIsOnline, true)
                    .groupBy(FeatureValue::getEntityId)
                    .orderByDesc(FeatureValue::getTimestamp);
            List<FeatureValue> onlineValues = featureValueMapper.selectList(wrapper);

            int total = onlineValues.size();
            int consistentCount = 0;
            List<String> inconsistentEntities = new ArrayList<>();

            for (FeatureValue ov : onlineValues) {
                String onlineKey = FEATURE_ONLINE_KEY_PREFIX + featureId + ":" + ov.getEntityId();
                String redisValue = redisTemplate.opsForValue().get(onlineKey).block();
                String offlineValue = getLatestValueFromDb(featureId, ov.getEntityId(), null);

                if (Objects.equals(redisValue, offlineValue)) {
                    consistentCount++;
                } else {
                    inconsistentEntities.add(ov.getEntityId());
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("featureId", featureId);
            result.put("totalEntities", total);
            result.put("consistentCount", consistentCount);
            result.put("consistencyRate", total > 0 ? (double) consistentCount / total : 1.0);
            result.put("inconsistentEntities", inconsistentEntities);
            return result;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<Void> deleteFeatureValues(String featureId, String entityId, LocalDateTime beforeTime) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<FeatureValue> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(FeatureValue::getFeatureId, featureId);
            if (entityId != null && !entityId.isEmpty()) {
                wrapper.eq(FeatureValue::getEntityId, entityId);
            }
            if (beforeTime != null) {
                wrapper.lt(FeatureValue::getTimestamp, beforeTime);
            }
            featureValueMapper.delete(wrapper);

            if (entityId != null && !entityId.isEmpty()) {
                String key = FEATURE_ONLINE_KEY_PREFIX + featureId + ":" + entityId;
                redisTemplate.delete(key).subscribe();
            }

            log.info("Deleted feature values: featureId={}, entityId={}, before={}",
                    featureId, entityId, beforeTime);
            return null;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Map<String, Object>> getFeatureStats(String featureId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<FeatureValue> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(FeatureValue::getFeatureId, featureId);
            Long totalValues = featureValueMapper.selectCount(wrapper);

            wrapper.groupBy(FeatureValue::getEntityId);
            Long totalEntities = featureValueMapper.selectCount(wrapper);

            LambdaQueryWrapper<FeatureValue> onlineWrapper = new LambdaQueryWrapper<>();
            onlineWrapper.eq(FeatureValue::getFeatureId, featureId)
                    .eq(FeatureValue::getIsOnline, true)
                    .groupBy(FeatureValue::getEntityId);
            Long onlineEntities = featureValueMapper.selectCount(onlineWrapper);

            LambdaQueryWrapper<FeatureValue> timeWrapper = new LambdaQueryWrapper<>();
            timeWrapper.eq(FeatureValue::getFeatureId, featureId)
                    .orderByDesc(FeatureValue::getTimestamp)
                    .last("LIMIT 1");
            FeatureValue latest = featureValueMapper.selectOne(timeWrapper);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("featureId", featureId);
            result.put("totalValues", totalValues);
            result.put("totalEntities", totalEntities);
            result.put("onlineEntities", onlineEntities);
            result.put("latestUpdate", latest != null ? latest.getTimestamp() : null);
            return result;
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
