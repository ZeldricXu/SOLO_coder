package com.datasync.service.conflict;

import com.datasync.model.ConflictRecord;
import com.datasync.model.DataVersion;

import java.util.List;
import java.util.Map;

public interface ConflictDetector {

    ConflictRecord detectConflict(
            String syncId,
            String taskId,
            String dataKey,
            Map<String, Object> sourceValue,
            Map<String, Object> targetValue,
            DataVersion sourceVersion,
            DataVersion targetVersion
    );

    boolean hasConflict(
            Map<String, Object> sourceValue,
            Map<String, Object> targetValue,
            DataVersion sourceVersion,
            DataVersion targetVersion
    );

    String detectConflictType(
            Map<String, Object> sourceValue,
            Map<String, Object> targetValue,
            DataVersion sourceVersion,
            DataVersion targetVersion
    );

    int getConflictPriority(String conflictType);

    boolean hasStructureConflict(Map<String, Object> sourceValue, Map<String, Object> targetValue);

    boolean hasTypeMismatch(Map<String, Object> sourceValue, Map<String, Object> targetValue);

    List<String> getAddedFields(Map<String, Object> sourceValue, Map<String, Object> targetValue);

    List<String> getRemovedFields(Map<String, Object> sourceValue, Map<String, Object> targetValue);

    List<String> getModifiedFields(Map<String, Object> sourceValue, Map<String, Object> targetValue);

    Map<String, String> getTypeMismatchFields(Map<String, Object> sourceValue, Map<String, Object> targetValue);

    String selectStrategyByConflictType(String conflictType, Integer priority, String defaultStrategy);
}
