package com.logmanager.service.pipeline;

import com.logmanager.domain.model.LogEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class LogEnricherChain {

    private final Map<String, LogEnricher> enrichers = new ConcurrentHashMap<>();
    private final List<String> enricherOrder = new ArrayList<>();

    public void addEnricher(String name, LogEnricher enricher) {
        addEnricher(name, enricher, enricherOrder.size());
    }

    public void addEnricher(String name, LogEnricher enricher, int order) {
        enrichers.put(name, enricher);
        if (order >= enricherOrder.size()) {
            enricherOrder.add(name);
        } else {
            enricherOrder.add(order, name);
        }
        log.info("Added log enricher '{}' at position {}", name, order);
    }

    public void removeEnricher(String name) {
        enrichers.remove(name);
        enricherOrder.remove(name);
        log.info("Removed log enricher: {}", name);
    }

    public LogEntry enrich(LogEntry logEntry) {
        LogEntry current = logEntry;
        for (String enricherName : enricherOrder) {
            LogEnricher enricher = enrichers.get(enricherName);
            if (enricher != null) {
                current = enricher.enrich(current);
            }
        }
        return current;
    }

    public int size() {
        return enrichers.size();
    }

    public List<String> getEnricherNames() {
        return new ArrayList<>(enricherOrder);
    }
}
