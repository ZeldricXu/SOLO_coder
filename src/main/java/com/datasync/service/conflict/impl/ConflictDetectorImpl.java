package com.datasync.service.conflict.impl;

import com.datasync.common.Constants;
import com.datasync.model.ConflictRecord;
import com.datasync.model.DataVersion;
import com.datasync.service.conflict.ConflictDetector;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ConflictDetectorImpl implements ConflictDetector {

    private static final Logger logger = LoggerFactory.getLogger(ConflictDetectorImpl.class);

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public ConflictRecord detectConflict(
            String syncId,
            String taskId,
            String dataKey,
            Map<String, Object> sourceValue,
            Map<String, Object> targetValue,
            DataVersion sourceVersion,
            DataVersion targetVersion
    ) {
        if (!hasConflict(sourceValue, targetValue, sourceVersion, targetVersion)) {
            return null;
        }

        ConflictRecord conflict = new ConflictRecord();
        conflict.setConflictId("conflict_" + UUID.randomUUID().toString().substring(0, 8));
        conflict.setSyncId(syncId);
        conflict.setTaskId(taskId);
        conflict.setDataKey(dataKey);
        conflict.setSourceValue(sourceValue);
        conflict.setTargetValue(targetValue);

        if (sourceVersion != null) {
            conflict.setSourceVersion(sourceVersion.getVersion());
        }
        if (targetVersion != null) {
            conflict.setTargetVersion(targetVersion.getVersion());
        }

        String conflictType = detectConflictType(sourceValue, targetValue, sourceVersion, targetVersion);
        conflict.setConflictType(conflictType);
        conflict.setPriority(getConflictPriority(conflictType));

        if (sourceValue != null) {
            conflict.setSourceFields(new ArrayList<>(sourceValue.keySet()));
        }
        if (targetValue != null) {
            conflict.setTargetFields(new ArrayList<>(targetValue.keySet()));
        }

        conflict.setAddedFields(getAddedFields(sourceValue, targetValue));
        conflict.setRemovedFields(getRemovedFields(sourceValue, targetValue));
        conflict.setModifiedFields(getModifiedFields(sourceValue, targetValue));
        conflict.setTypeMismatchFields(getTypeMismatchFields(sourceValue, targetValue));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("hasStructureConflict", hasStructureConflict(sourceValue, targetValue));
        details.put("hasTypeMismatch", hasTypeMismatch(sourceValue, targetValue));
        details.put("addedFieldsCount", conflict.getAddedFields().size());
        details.put("removedFieldsCount", conflict.getRemovedFields().size());
        details.put("modifiedFieldsCount", conflict.getModifiedFields().size());
        details.put("typeMismatchCount", conflict.getTypeMismatchFields().size());
        conflict.setConflictDetails(details);

        conflict.setStatus(Constants.CONFLICT_STATUS_PENDING);

        logger.info("Conflict detected: {} (type: {}, priority: {}) for key {}",
                conflict.getConflictId(), conflictType, conflict.getPriority(), dataKey);

        return conflict;
    }

    @Override
    public boolean hasConflict(
            Map<String, Object> sourceValue,
            Map<String, Object> targetValue,
            DataVersion sourceVersion,
            DataVersion targetVersion
    ) {
        if (sourceVersion != null && targetVersion != null) {
            if (!Objects.equals(sourceVersion.getVersion(), targetVersion.getVersion())) {
                logger.debug("Version mismatch detected");
                return true;
            }
        }

        if (hasStructureConflict(sourceValue, targetValue)) {
            logger.debug("Structure conflict detected");
            return true;
        }

        if (hasTypeMismatch(sourceValue, targetValue)) {
            logger.debug("Type mismatch detected");
            return true;
        }

        if (!haveSameContent(sourceValue, targetValue)) {
            logger.debug("Content mismatch detected");
            return true;
        }

        return false;
    }

    @Override
    public String detectConflictType(
            Map<String, Object> sourceValue,
            Map<String, Object> targetValue,
            DataVersion sourceVersion,
            DataVersion targetVersion
    ) {
        List<String> conflictTypes = new ArrayList<>();

        boolean hasVersionConflict = false;
        boolean hasStructureConflict = false;
        boolean hasTypeMismatch = false;
        boolean hasContentConflict = false;

        if (sourceVersion != null && targetVersion != null) {
            if (!Objects.equals(sourceVersion.getVersion(), targetVersion.getVersion())) {
                hasVersionConflict = true;
                conflictTypes.add(Constants.CONFLICT_TYPE_VERSION);
            }
        }

        if (hasStructureConflict(sourceValue, targetValue)) {
            hasStructureConflict = true;
            conflictTypes.add(Constants.CONFLICT_TYPE_STRUCTURE);
        }

        if (hasTypeMismatch(sourceValue, targetValue)) {
            hasTypeMismatch = true;
            conflictTypes.add(Constants.CONFLICT_TYPE_TYPE_MISMATCH);
        }

        if (!haveSameContent(sourceValue, targetValue)) {
            hasContentConflict = true;
            conflictTypes.add(Constants.CONFLICT_TYPE_CONTENT);
        }

        if (conflictTypes.size() > 1) {
            return Constants.CONFLICT_TYPE_MIXED;
        }

        if (conflictTypes.size() == 1) {
            return conflictTypes.get(0);
        }

        return null;
    }

    @Override
    public int getConflictPriority(String conflictType) {
        if (conflictType == null) {
            return Constants.CONFLICT_PRIORITY_MEDIUM;
        }

        switch (conflictType) {
            case Constants.CONFLICT_TYPE_STRUCTURE:
            case Constants.CONFLICT_TYPE_TYPE_MISMATCH:
                return Constants.CONFLICT_PRIORITY_CRITICAL;
            case Constants.CONFLICT_TYPE_MIXED:
                return Constants.CONFLICT_PRIORITY_HIGH;
            case Constants.CONFLICT_TYPE_VERSION:
                return Constants.CONFLICT_PRIORITY_MEDIUM;
            case Constants.CONFLICT_TYPE_CONTENT:
                return Constants.CONFLICT_PRIORITY_LOW;
            default:
                return Constants.CONFLICT_PRIORITY_MEDIUM;
        }
    }

    @Override
    public boolean hasStructureConflict(Map<String, Object> sourceValue, Map<String, Object> targetValue) {
        if (sourceValue == null && targetValue == null) {
            return false;
        }
        if (sourceValue == null || targetValue == null) {
            return true;
        }

        Set<String> sourceKeys = new HashSet<>(sourceValue.keySet());
        Set<String> targetKeys = new HashSet<>(targetValue.keySet());

        Set<String> sourceOnly = new HashSet<>(sourceKeys);
        sourceOnly.removeAll(targetKeys);

        Set<String> targetOnly = new HashSet<>(targetKeys);
        targetOnly.removeAll(sourceKeys);

        return !sourceOnly.isEmpty() || !targetOnly.isEmpty();
    }

    @Override
    public boolean hasTypeMismatch(Map<String, Object> sourceValue, Map<String, Object> targetValue) {
        if (sourceValue == null || targetValue == null) {
            return false;
        }

        Set<String> commonKeys = new HashSet<>(sourceValue.keySet());
        commonKeys.retainAll(targetValue.keySet());

        for (String key : commonKeys) {
            Object sourceVal = sourceValue.get(key);
            Object targetVal = targetValue.get(key);

            if (sourceVal == null || targetVal == null) {
                continue;
            }

            if (!isCompatibleType(sourceVal, targetVal)) {
                return true;
            }
        }
        return false;
    }

    private boolean isCompatibleType(Object sourceVal, Object targetVal) {
        if (sourceVal.getClass().equals(targetVal.getClass())) {
            return true;
        }

        if (isNumericType(sourceVal) && isNumericType(targetVal)) {
            return true;
        }

        if (sourceVal instanceof CharSequence && targetVal instanceof CharSequence) {
            return true;
        }

        if ((sourceVal instanceof Collection) && (targetVal instanceof Collection)) {
            return true;
        }

        if ((sourceVal instanceof Map) && (targetVal instanceof Map)) {
            return true;
        }

        return false;
    }

    private boolean isNumericType(Object obj) {
        return obj instanceof Number ||
               obj.getClass().isPrimitive() && (
                   obj.getClass().equals(int.class) ||
                   obj.getClass().equals(long.class) ||
                   obj.getClass().equals(double.class) ||
                   obj.getClass().equals(float.class) ||
                   obj.getClass().equals(short.class) ||
                   obj.getClass().equals(byte.class)
               );
    }

    @Override
    public List<String> getAddedFields(Map<String, Object> sourceValue, Map<String, Object> targetValue) {
        if (sourceValue == null) {
            return new ArrayList<>();
        }
        if (targetValue == null) {
            return new ArrayList<>(sourceValue.keySet());
        }

        Set<String> sourceKeys = new HashSet<>(sourceValue.keySet());
        Set<String> targetKeys = new HashSet<>(targetValue.keySet());

        sourceKeys.removeAll(targetKeys);
        return new ArrayList<>(sourceKeys);
    }

    @Override
    public List<String> getRemovedFields(Map<String, Object> sourceValue, Map<String, Object> targetValue) {
        if (targetValue == null) {
            return new ArrayList<>();
        }
        if (sourceValue == null) {
            return new ArrayList<>(targetValue.keySet());
        }

        Set<String> sourceKeys = new HashSet<>(sourceValue.keySet());
        Set<String> targetKeys = new HashSet<>(targetValue.keySet());

        targetKeys.removeAll(sourceKeys);
        return new ArrayList<>(targetKeys);
    }

    @Override
    public List<String> getModifiedFields(Map<String, Object> sourceValue, Map<String, Object> targetValue) {
        if (sourceValue == null || targetValue == null) {
            return new ArrayList<>();
        }

        List<String> modifiedFields = new ArrayList<>();
        Set<String> commonKeys = new HashSet<>(sourceValue.keySet());
        commonKeys.retainAll(targetValue.keySet());

        for (String key : commonKeys) {
            Object sourceVal = sourceValue.get(key);
            Object targetVal = targetValue.get(key);

            if (!Objects.equals(sourceVal, targetVal)) {
                modifiedFields.add(key);
            }
        }

        return modifiedFields;
    }

    @Override
    public Map<String, String> getTypeMismatchFields(Map<String, Object> sourceValue, Map<String, Object> targetValue) {
        Map<String, String> mismatches = new LinkedHashMap<>();

        if (sourceValue == null || targetValue == null) {
            return mismatches;
        }

        Set<String> commonKeys = new HashSet<>(sourceValue.keySet());
        commonKeys.retainAll(targetValue.keySet());

        for (String key : commonKeys) {
            Object sourceVal = sourceValue.get(key);
            Object targetVal = targetValue.get(key);

            if (sourceVal == null || targetVal == null) {
                continue;
            }

            if (!isCompatibleType(sourceVal, targetVal)) {
                mismatches.put(key,
                        sourceVal.getClass().getSimpleName() + " -> " + targetVal.getClass().getSimpleName());
            }
        }

        return mismatches;
    }

    @Override
    public String selectStrategyByConflictType(String conflictType, Integer priority, String defaultStrategy) {
        if (conflictType == null) {
            return defaultStrategy;
        }

        int actualPriority = priority != null ? priority : getConflictPriority(conflictType);

        if (actualPriority <= Constants.CONFLICT_PRIORITY_CRITICAL) {
            return Constants.CONFLICT_STRATEGY_MANUAL;
        }

        switch (conflictType) {
            case Constants.CONFLICT_TYPE_VERSION:
                return Constants.CONFLICT_STRATEGY_SOURCE_PRIORITY;
            case Constants.CONFLICT_TYPE_CONTENT:
                return Constants.CONFLICT_STRATEGY_SOURCE_PRIORITY;
            case Constants.CONFLICT_TYPE_STRUCTURE:
                return Constants.CONFLICT_STRATEGY_MANUAL;
            case Constants.CONFLICT_TYPE_TYPE_MISMATCH:
                return Constants.CONFLICT_STRATEGY_MANUAL;
            case Constants.CONFLICT_TYPE_MIXED:
                if (actualPriority <= Constants.CONFLICT_PRIORITY_HIGH) {
                    return Constants.CONFLICT_STRATEGY_MANUAL;
                }
                return defaultStrategy;
            default:
                return defaultStrategy;
        }
    }

    private boolean haveSameContent(Map<String, Object> sourceValue, Map<String, Object> targetValue) {
        if (sourceValue == null && targetValue == null) {
            return true;
        }
        if (sourceValue == null || targetValue == null) {
            return false;
        }

        Map<String, Object> normalizedSource = normalizeMap(sourceValue);
        Map<String, Object> normalizedTarget = normalizeMap(targetValue);

        return Objects.equals(normalizedSource, normalizedTarget);
    }

    private Map<String, Object> normalizeMap(Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        Map<String, Object> normalized = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map) {
                value = normalizeMap((Map<String, Object>) value);
            }
            normalized.put(entry.getKey(), value);
        }
        return normalized;
    }
}
