package com.logmanager.service.pipeline.enricher;

import com.logmanager.domain.model.LogEntry;
import com.logmanager.service.pipeline.LogEnricher;
import java.util.UUID;

public class IdEnricher implements LogEnricher {
    @Override
    public LogEntry enrich(LogEntry logEntry) {
        if (logEntry.getId() == null) {
            logEntry.setId(UUID.randomUUID().toString());
        }
        return logEntry;
    }
}
