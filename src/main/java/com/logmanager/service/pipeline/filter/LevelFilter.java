package com.logmanager.service.pipeline.filter;

import com.logmanager.common.enums.LogLevel;
import com.logmanager.domain.model.LogEntry;
import com.logmanager.service.pipeline.LogFilter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class LevelFilter implements LogFilter {
    private final LogLevel minimumLevel;

    @Override
    public boolean accept(LogEntry logEntry) {
        if (logEntry.getLevel() == null) {
            return false;
        }
        return logEntry.getLevel().isHigherOrEqual(minimumLevel);
    }
}
