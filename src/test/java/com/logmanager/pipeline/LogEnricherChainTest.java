package com.logmanager.pipeline;

import com.logmanager.domain.model.LogEntry;
import com.logmanager.service.pipeline.LogEnricherChain;
import com.logmanager.service.pipeline.enricher.IdEnricher;
import com.logmanager.service.pipeline.enricher.TimestampEnricher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LogEnricherChainTest {

    private LogEnricherChain enricherChain;

    @BeforeEach
    void setUp() {
        enricherChain = new LogEnricherChain();
    }

    @Test
    void shouldEnrichWithTimestamp() {
        enricherChain.addEnricher("timestamp", new TimestampEnricher());

        LogEntry entry = new LogEntry();
        LogEntry enriched = enricherChain.enrich(entry);

        assertNotNull(enriched.getTimestamp());
        assertNotNull(enriched.getCreatedAt());
    }

    @Test
    void shouldEnrichWithId() {
        enricherChain.addEnricher("id", new IdEnricher());

        LogEntry entry = new LogEntry();
        LogEntry enriched = enricherChain.enrich(entry);

        assertNotNull(enriched.getId());
    }

    @Test
    void shouldNotOverrideExistingId() {
        enricherChain.addEnricher("id", new IdEnricher());

        LogEntry entry = new LogEntry();
        entry.setId("existing-id");
        LogEntry enriched = enricherChain.enrich(entry);

        assertEquals("existing-id", enriched.getId());
    }

    @Test
    void shouldApplyMultipleEnrichers() {
        enricherChain.addEnricher("timestamp", new TimestampEnricher(), 0);
        enricherChain.addEnricher("id", new IdEnricher(), 1);

        LogEntry entry = new LogEntry();
        LogEntry enriched = enricherChain.enrich(entry);

        assertNotNull(enriched.getId());
        assertNotNull(enriched.getTimestamp());
        assertNotNull(enriched.getCreatedAt());
    }

    @Test
    void shouldRespectEnricherOrder() {
        enricherChain.addEnricher("first", entry -> {
            entry.setMessage("first");
            return entry;
        }, 0);
        enricherChain.addEnricher("second", entry -> {
            entry.setMessage(entry.getMessage() + "-second");
            return entry;
        }, 1);

        LogEntry entry = new LogEntry();
        LogEntry enriched = enricherChain.enrich(entry);

        assertEquals("first-second", enriched.getMessage());
    }
}
