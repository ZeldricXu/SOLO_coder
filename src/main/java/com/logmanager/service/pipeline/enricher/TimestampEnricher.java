package com.logmanager.service.pipeline.enricher;

import com.logmanager.domain.model.LogEntry;
import com.logmanager.service.pipeline.LogEnricher;
import java.time.Instant;

public class TimestampEnricher implements LogEnricher {
    @Override
    public LogEntry enrich(LogEntry logEntry) {
        Instant now = Instant.now();
        if (logEntry.getTimestamp() == null) {
            logEntry.setTimestamp(now);
        }
        if (logEntry.getCreatedAt() == null) {
            logEntry.setCreatedAt(now);
        }
        return logEntry;
    }
}
