package com.dynamiclog.deviceshadow.service;

import com.dynamiclog.common.entity.DeviceShadow;
import com.dynamiclog.common.exception.ResourceNotFoundException;
import com.dynamiclog.common.util.IdGenerator;
import com.dynamiclog.common.util.JsonUtils;
import com.dynamiclog.persistence.mapper.DeviceShadowMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceShadowService {

    private final DeviceShadowMapper deviceShadowMapper;

    private final Cache<String, DeviceShadow> shadowCache = Caffeine.newBuilder()
            .expireAfterAccess(5, TimeUnit.MINUTES)
            .maximumSize(10000)
            .build();

    public Mono<DeviceShadow> createShadow(DeviceShadow shadow) {
        return Mono.fromCallable(() -> {
            shadow.setId(IdGenerator.generateId("dev"));
            shadow.setVersion(0);
            shadow.setOnline(false);
            if (shadow.getDesiredState() == null) shadow.setDesiredState(new HashMap<>());
            if (shadow.getReportedState() == null) shadow.setReportedState(new HashMap<>());
            shadow.setDeltaState(calculateDelta(shadow.getDesiredState(), shadow.getReportedState()));
            deviceShadowMapper.insert(shadow);
            shadowCache.put(shadow.getDeviceId(), shadow);
            log.info("Device shadow created: deviceId={}", shadow.getDeviceId());
            return shadow;
        });
    }

    public Mono<DeviceShadow> getShadow(String deviceId) {
        return Mono.fromCallable(() -> {
            DeviceShadow cached = shadowCache.getIfPresent(deviceId);
            if (cached != null) {
                return cached;
            }
            DeviceShadow shadow = deviceShadowMapper.findByDeviceId(deviceId);
            if (shadow == null) {
                throw new ResourceNotFoundException("DeviceShadow", deviceId);
            }
            shadowCache.put(deviceId, shadow);
            return shadow;
        });
    }

    public Mono<DeviceShadow> updateDesiredState(String deviceId, Map<String, Object> desiredState) {
        return Mono.fromCallable(() -> {
            DeviceShadow shadow = getOrCreateShadow(deviceId);
            Integer currentVersion = deviceShadowMapper.getCurrentVersion(deviceId);
            int newVersion = (currentVersion != null ? currentVersion : 0) + 1;

            shadow.setDesiredState(desiredState);
            shadow.setDeltaState(calculateDelta(desiredState, shadow.getReportedState()));
            shadow.setVersion(newVersion);
            shadow.setLastDesiredUpdatedAt(LocalDateTime.now());

            deviceShadowMapper.updateById(shadow);
            shadowCache.put(deviceId, shadow);

            log.info("Device desired state updated: deviceId={}, version={}", deviceId, newVersion);
            return shadow;
        });
    }

    public Mono<DeviceShadow> updateReportedState(String deviceId, Map<String, Object> reportedState) {
        return Mono.fromCallable(() -> {
            DeviceShadow shadow = getOrCreateShadow(deviceId);
            Integer currentVersion = deviceShadowMapper.getCurrentVersion(deviceId);
            int newVersion = (currentVersion != null ? currentVersion : 0) + 1;

            shadow.setReportedState(reportedState);
            shadow.setDeltaState(calculateDelta(shadow.getDesiredState(), reportedState));
            shadow.setVersion(newVersion);
            shadow.setLastReportedAt(LocalDateTime.now());
            shadow.setOnline(true);
            shadow.setConnectionStatus("connected");

            deviceShadowMapper.updateById(shadow);
            shadowCache.put(deviceId, shadow);

            if (shadow.getDeltaState() != null && shadow.getDeltaState().isEmpty()) {
                log.info("Device in sync: deviceId={}", deviceId);
            }

            return shadow;
        });
    }

    public Mono<DeviceShadow> patchDesiredState(String deviceId, Map<String, Object> patch) {
        return getShadow(deviceId)
                .flatMap(shadow -> {
                    Map<String, Object> desired = new HashMap<>(shadow.getDesiredState());
                    desired.putAll(patch);
                    return updateDesiredState(deviceId, desired);
                });
    }

    public Mono<DeviceShadow> patchReportedState(String deviceId, Map<String, Object> patch) {
        return getShadow(deviceId)
                .flatMap(shadow -> {
                    Map<String, Object> reported = new HashMap<>(shadow.getReportedState());
                    reported.putAll(patch);
                    return updateReportedState(deviceId, reported);
                });
    }

    public Mono<Void> deleteShadow(String deviceId) {
        return Mono.fromRunnable(() -> {
            DeviceShadow shadow = deviceShadowMapper.findByDeviceId(deviceId);
            if (shadow != null) {
                deviceShadowMapper.deleteById(shadow.getId());
                shadowCache.invalidate(deviceId);
                log.info("Device shadow deleted: deviceId={}", deviceId);
            }
        });
    }

    public Mono<Void> markOffline(String deviceId) {
        return Mono.fromRunnable(() -> {
            DeviceShadow shadow = deviceShadowMapper.findByDeviceId(deviceId);
            if (shadow != null) {
                shadow.setOnline(false);
                shadow.setConnectionStatus("disconnected");
                deviceShadowMapper.updateById(shadow);
                shadowCache.invalidate(deviceId);
                log.info("Device marked offline: deviceId={}", deviceId);
            }
        });
    }

    public Mono<Map<String, Object>> getDelta(String deviceId) {
        return getShadow(deviceId)
                .map(DeviceShadow::getDeltaState);
    }

    private DeviceShadow getOrCreateShadow(String deviceId) {
        DeviceShadow shadow = deviceShadowMapper.findByDeviceId(deviceId);
        if (shadow == null) {
            shadow = new DeviceShadow();
            shadow.setId(IdGenerator.generateId("dev"));
            shadow.setDeviceId(deviceId);
            shadow.setVersion(0);
            shadow.setDesiredState(new HashMap<>());
            shadow.setReportedState(new HashMap<>());
            shadow.setDeltaState(new HashMap<>());
            shadow.setOnline(false);
            shadow.setConnectionStatus("new");
            deviceShadowMapper.insert(shadow);
        }
        return shadow;
    }

    private Map<String, Object> calculateDelta(Map<String, Object> desired, Map<String, Object> reported) {
        Map<String, Object> delta = new HashMap<>();
        if (desired == null) return delta;

        for (Map.Entry<String, Object> entry : desired.entrySet()) {
            String key = entry.getKey();
            Object desiredValue = entry.getValue();
            Object reportedValue = reported != null ? reported.get(key) : null;

            if (!valuesEqual(desiredValue, reportedValue)) {
                delta.put(key, desiredValue);
            }
        }
        return delta;
    }

    private boolean valuesEqual(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        if (a instanceof Map && b instanceof Map) {
            return JsonUtils.toJson(a).equals(JsonUtils.toJson(b));
        }
        return a.equals(b);
    }
}
