package com.datasync.service.strategy;

import com.datasync.model.ConflictRecord;

public interface StrategyExtension {

    String getConflictType();

    String resolveStrategy(ConflictRecord conflict, String defaultStrategy);

    boolean appliesTo(ConflictRecord conflict);

    default int getPriority() {
        return 0;
    }
}
