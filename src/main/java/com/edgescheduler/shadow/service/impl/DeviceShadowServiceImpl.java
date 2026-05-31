package com.edgescheduler.shadow.service.impl;

import cn.hutool.core.util.IdUtil;
import com.edgescheduler.common.exception.BusinessException;
import com.edgescheduler.common.exception.OptimisticLockException;
import com.edgescheduler.shadow.dto.DeviceShadowDTO;
import com.edgescheduler.shadow.entity.DeviceShadow;
import com.edgescheduler.shadow.entity.ShadowOperationLog;
import com.edgescheduler.shadow.mapper.DeviceShadowMapper;
import com.edgescheduler.shadow.mapper.ShadowOperationLogMapper;
import com.edgescheduler.shadow.service.DeviceShadowService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceShadowServiceImpl implements DeviceShadowService {

    private final DeviceShadowMapper shadowMapper;
    private final ShadowOperationLogMapper logMapper;
    private final MeterRegistry meterRegistry;

    @Value("${edge.scheduler.rule.retry-max-attempts:3}")
    private int maxRetryAttempts;

    @Override
    @Transactional
    public DeviceShadowDTO createShadow(String deviceKey) {
        DeviceShadow existing = shadowMapper.selectByDeviceKey(deviceKey);
        if (existing != null) {
            return convertToDTO(existing);
        }

        DeviceShadow shadow = new DeviceShadow();
        shadow.setDeviceKey(deviceKey);
        shadow.setVersion(1);
        shadow.setDesired(new HashMap<>());
        shadow.setReported(new HashMap<>());
        shadow.setDelta(new HashMap<>());

        shadowMapper.insert(shadow);
        logOperation(shadow, ShadowOperationLog.OperationType.SYNC, null, shadow.getDesired(), null);

        meterRegistry.counter("shadow.create.total").increment();
        log.info("Device shadow created: {}", deviceKey);

        return convertToDTO(shadow);
    }

    @Override
    public DeviceShadowDTO getShadow(String deviceKey) {
        DeviceShadow shadow = getShadowEntity(deviceKey);
        return convertToDTO(shadow);
    }

    @Override
    @Transactional
    public DeviceShadowDTO updateDesired(String deviceKey, Map<String, Object> desired, String operator) {
        int attempts = 0;
        while (attempts < maxRetryAttempts) {
            DeviceShadow shadow = getShadowEntity(deviceKey);
            Map<String, Object> beforeState = new HashMap<>(shadow.getDesired() != null ? shadow.getDesired() : new HashMap<>());

            Map<String, Object> newDesired = new HashMap<>(beforeState);
            newDesired.putAll(desired);

            shadow.setDesired(newDesired);
            shadow.setLastDesiredUpdateAt(LocalDateTime.now());
            shadow.setDelta(calculateDelta(newDesired, shadow.getReported()));

            int updated = shadowMapper.updateById(shadow);
            if (updated > 0) {
                Map<String, Object> changeSet = calculateChangeSet(beforeState, newDesired);
                logOperation(shadow, ShadowOperationLog.OperationType.DESIRED_UPDATE, beforeState, newDesired, operator);
                meterRegistry.counter("shadow.desired.update.total").increment();
                log.info("Device shadow desired updated: {}", deviceKey);
                return convertToDTO(shadow);
            }
            attempts++;
        }
        throw new OptimisticLockException("Failed to update desired state after " + maxRetryAttempts + " attempts");
    }

    @Override
    @Transactional
    public DeviceShadowDTO updateReported(String deviceKey, Map<String, Object> reported, String operator) {
        int attempts = 0;
        while (attempts < maxRetryAttempts) {
            DeviceShadow shadow = getShadowEntity(deviceKey);
            Map<String, Object> beforeState = new HashMap<>(shadow.getReported() != null ? shadow.getReported() : new HashMap<>());

            Map<String, Object> newReported = new HashMap<>(beforeState);
            newReported.putAll(reported);

            shadow.setReported(newReported);
            shadow.setLastReportedUpdateAt(LocalDateTime.now());
            shadow.setDelta(calculateDelta(shadow.getDesired(), newReported));

            int updated = shadowMapper.updateById(shadow);
            if (updated > 0) {
                Map<String, Object> changeSet = calculateChangeSet(beforeState, newReported);
                logOperation(shadow, ShadowOperationLog.OperationType.REPORTED_UPDATE, beforeState, newReported, operator);
                meterRegistry.counter("shadow.reported.update.total").increment();
                log.info("Device shadow reported updated: {}", deviceKey);
                return convertToDTO(shadow);
            }
            attempts++;
        }
        throw new OptimisticLockException("Failed to update reported state after " + maxRetryAttempts + " attempts");
    }

    @Override
    @Transactional
    public DeviceShadowDTO mergeShadow(String deviceKey, Map<String, Object> state, String operator) {
        int attempts = 0;
        while (attempts < maxRetryAttempts) {
            DeviceShadow shadow = getShadowEntity(deviceKey);
            Map<String, Object> beforeReported = new HashMap<>(shadow.getReported() != null ? shadow.getReported() : new HashMap<>());
            Map<String, Object> beforeDesired = new HashMap<>(shadow.getDesired() != null ? shadow.getDesired() : new HashMap<>());

            Map<String, Object> reportedSection = (Map<String, Object>) state.get("reported");
            Map<String, Object> desiredSection = (Map<String, Object>) state.get("desired");

            if (reportedSection != null) {
                Map<String, Object> newReported = new HashMap<>(beforeReported);
                newReported.putAll(reportedSection);
                shadow.setReported(newReported);
                shadow.setLastReportedUpdateAt(LocalDateTime.now());
            }

            if (desiredSection != null) {
                Map<String, Object> newDesired = new HashMap<>(beforeDesired);
                newDesired.putAll(desiredSection);
                shadow.setDesired(newDesired);
                shadow.setLastDesiredUpdateAt(LocalDateTime.now());
            }

            shadow.setDelta(calculateDelta(shadow.getDesired(), shadow.getReported()));

            int updated = shadowMapper.updateById(shadow);
            if (updated > 0) {
                Map<String, Object> mergedState = new HashMap<>();
                mergedState.put("reported", shadow.getReported());
                mergedState.put("desired", shadow.getDesired());
                logOperation(shadow, ShadowOperationLog.OperationType.MERGE, state, mergedState, operator);
                meterRegistry.counter("shadow.merge.total").increment();
                log.info("Device shadow merged: {}", deviceKey);
                return convertToDTO(shadow);
            }
            attempts++;
        }
        throw new OptimisticLockException("Failed to merge shadow after " + maxRetryAttempts + " attempts");
    }

    @Override
    @Transactional
    public DeviceShadowDTO syncShadow(String deviceKey) {
        DeviceShadow shadow = getShadowEntity(deviceKey);
        shadow.setLastSyncAt(LocalDateTime.now());
        shadow.setDelta(calculateDelta(shadow.getDesired(), shadow.getReported()));
        shadowMapper.updateById(shadow);

        logOperation(shadow, ShadowOperationLog.OperationType.SYNC, null, shadow.getDelta(), "system");
        meterRegistry.counter("shadow.sync.total").increment();
        log.info("Device shadow synced: {}", deviceKey);

        return convertToDTO(shadow);
    }

    @Override
    @Transactional
    public void deleteShadow(String deviceKey) {
        DeviceShadow shadow = getShadowEntity(deviceKey);
        shadowMapper.deleteById(shadow.getId());
        log.info("Device shadow deleted: {}", deviceKey);
    }

    @Override
    public Map<String, Object> calculateDelta(Map<String, Object> desired, Map<String, Object> reported) {
        Map<String, Object> delta = new HashMap<>();

        if (desired == null || desired.isEmpty()) {
            return delta;
        }

        Map<String, Object> reportedSafe = reported != null ? reported : new HashMap<>();

        for (Map.Entry<String, Object> entry : desired.entrySet()) {
            String key = entry.getKey();
            Object desiredValue = entry.getValue();
            Object reportedValue = reportedSafe.get(key);

            if (!valuesEqual(desiredValue, reportedValue)) {
                delta.put(key, Map.of(
                        "desired", desiredValue,
                        "reported", reportedValue
                ));
            }
        }

        return delta;
    }

    private boolean valuesEqual(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        if (a instanceof Number && b instanceof Number) {
            return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        }
        return a.equals(b);
    }

    private Map<String, Object> calculateChangeSet(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> changes = new HashMap<>();

        for (Map.Entry<String, Object> entry : after.entrySet()) {
            String key = entry.getKey();
            Object afterValue = entry.getValue();
            Object beforeValue = before.get(key);

            if (!valuesEqual(beforeValue, afterValue)) {
                changes.put(key, Map.of(
                        "before", beforeValue,
                        "after", afterValue
                ));
            }
        }

        return changes;
    }

    @Override
    public List<ShadowOperationLog> getOperationLogs(String deviceKey, int limit) {
        return logMapper.selectByDeviceKey(deviceKey, limit);
    }

    @Override
    public DeviceShadowDTO getShadowStatus(String deviceKey) {
        DeviceShadow shadow = getShadowEntity(deviceKey);
        DeviceShadowDTO dto = new DeviceShadowDTO();
        dto.setDeviceKey(deviceKey);
        dto.setVersion(shadow.getVersion());
        dto.setDelta(shadow.getDelta());
        dto.setLastSyncAt(shadow.getLastSyncAt());
        dto.setLastDesiredUpdateAt(shadow.getLastDesiredUpdateAt());
        dto.setLastReportedUpdateAt(shadow.getLastReportedUpdateAt());
        dto.setCreatedAt(shadow.getCreatedAt());
        dto.setUpdatedAt(shadow.getUpdatedAt());
        return dto;
    }

    private void logOperation(DeviceShadow shadow, String operationType,
                              Map<String, Object> beforeState, Map<String, Object> afterState,
                              String operator) {
        ShadowOperationLog log = new ShadowOperationLog();
        log.setLogId("log_" + IdUtil.getSnowflakeNextIdStr());
        log.setDeviceKey(shadow.getDeviceKey());
        log.setOperationType(operationType);
        log.setBeforeState(beforeState);
        log.setAfterState(afterState);
        log.setChangeSet(afterState != null ? calculateDelta(beforeState, afterState) : null);
        log.setOperator(operator);
        log.setVersion(shadow.getVersion());
        logMapper.insert(log);
    }

    private DeviceShadow getShadowEntity(String deviceKey) {
        DeviceShadow shadow = shadowMapper.selectByDeviceKey(deviceKey);
        if (shadow == null) {
            throw BusinessException.notFound("Device shadow not found: " + deviceKey);
        }
        return shadow;
    }

    private DeviceShadowDTO convertToDTO(DeviceShadow shadow) {
        DeviceShadowDTO dto = new DeviceShadowDTO();
        BeanUtils.copyProperties(shadow, dto);
        return dto;
    }
}
