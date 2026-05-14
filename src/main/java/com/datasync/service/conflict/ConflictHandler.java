package com.datasync.service.conflict;

import com.datasync.model.ConflictRecord;
import com.datasync.model.ConflictStrategyConfig;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ConflictHandler {

    ConflictRecord handleConflict(ConflictRecord conflict, String strategy);

    ConflictRecord handleConflictWithAutoStrategy(ConflictRecord conflict, String defaultStrategy);

    ConflictRecord handleConflictWithConfig(ConflictRecord conflict, ConflictStrategyConfig strategyConfig);

    Optional<ConflictRecord> getConflict(String conflictId);

    List<ConflictRecord> getConflictsBySyncId(String syncId);

    List<ConflictRecord> getConflictsByTaskId(String taskId);

    List<ConflictRecord> getPendingConflicts();

    List<ConflictRecord> getConflictsByPriority(int priority);

    List<ConflictRecord> getConflictsByType(String conflictType);

    List<ConflictRecord> getAllConflicts();

    List<ConflictRecord> getConflictsSortedByPriority();

    ConflictRecord resolveManualConflict(String conflictId, Map<String, Object> resolutionValue);

    void saveConflict(ConflictRecord conflict);

    int getConflictCountByStatus(String status);

    int getConflictCountByType(String conflictType);

    int getConflictCountByPriority(int priority);

    void loadAllConflictsFromPersistence();
}
