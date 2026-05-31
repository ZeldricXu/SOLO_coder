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
public class LogFilterChain {

    private final Map<String, LogFilter> filters = new ConcurrentHashMap<>();
    private final List<String> filterOrder = new ArrayList<>();

    public void addFilter(String name, LogFilter filter) {
        addFilter(name, filter, filterOrder.size());
    }

    public void addFilter(String name, LogFilter filter, int order) {
        filters.put(name, filter);
        if (order >= filterOrder.size()) {
            filterOrder.add(name);
        } else {
            filterOrder.add(order, name);
        }
        log.info("Added log filter '{}' at position {}", name, order);
    }

    public void removeFilter(String name) {
        filters.remove(name);
        filterOrder.remove(name);
        log.info("Removed log filter: {}", name);
    }

    public boolean doFilter(LogEntry logEntry) {
        for (String filterName : filterOrder) {
            LogFilter filter = filters.get(filterName);
            if (filter != null && !filter.accept(logEntry)) {
                log.debug("Log entry filtered out by filter: {}", filterName);
                return false;
            }
        }
        return true;
    }

    public int size() {
        return filters.size();
    }

    public List<String> getFilterNames() {
        return new ArrayList<>(filterOrder);
    }
}
