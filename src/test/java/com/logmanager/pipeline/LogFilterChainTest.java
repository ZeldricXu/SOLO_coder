package com.logmanager.pipeline;

import com.logmanager.common.enums.LogLevel;
import com.logmanager.domain.model.LogEntry;
import com.logmanager.service.pipeline.LogFilterChain;
import com.logmanager.service.pipeline.filter.LevelFilter;
import com.logmanager.service.pipeline.filter.ServiceNameFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Set;

class LogFilterChainTest {

    private LogFilterChain filterChain;

    @BeforeEach
    void setUp() {
        filterChain = new LogFilterChain();
    }

    @Test
    void shouldPassAllFilters() {
        filterChain.addFilter("level", new LevelFilter(LogLevel.INFO));
        filterChain.addFilter("service", ServiceNameFilter.allow(Set.of("order-service")));

        LogEntry entry = new LogEntry();
        entry.setLevel(LogLevel.INFO);
        entry.setServiceName("order-service");

        assertTrue(filterChain.doFilter(entry));
    }

    @Test
    void shouldFilterOutByLevel() {
        filterChain.addFilter("level", new LevelFilter(LogLevel.WARN));

        LogEntry entry = new LogEntry();
        entry.setLevel(LogLevel.INFO);

        assertFalse(filterChain.doFilter(entry));
    }

    @Test
    void shouldFilterOutByServiceName() {
        filterChain.addFilter("service", ServiceNameFilter.allow(Set.of("order-service")));

        LogEntry entry = new LogEntry();
        entry.setServiceName("payment-service");

        assertFalse(filterChain.doFilter(entry));
    }

    @Test
    void shouldFilterOutByDenyService() {
        filterChain.addFilter("service", ServiceNameFilter.deny(Set.of("debug-service")));

        LogEntry entry = new LogEntry();
        entry.setServiceName("debug-service");

        assertFalse(filterChain.doFilter(entry));
    }

    @Test
    void shouldRespectFilterOrder() {
        filterChain.addFilter("first", entry -> {
            entry.setMessage("first-passed");
            return true;
        }, 0);
        filterChain.addFilter("second", entry -> {
            entry.setMessage(entry.getMessage() + "-second-passed");
            return true;
        }, 1);

        LogEntry entry = new LogEntry();
        entry.setMessage("");
        assertTrue(filterChain.doFilter(entry));
        assertEquals("first-passed-second-passed", entry.getMessage());
    }

    @Test
    void shouldRemoveFilter() {
        filterChain.addFilter("level", new LevelFilter(LogLevel.WARN));
        assertEquals(1, filterChain.size());

        filterChain.removeFilter("level");
        assertEquals(0, filterChain.size());

        LogEntry entry = new LogEntry();
        entry.setLevel(LogLevel.INFO);
        assertTrue(filterChain.doFilter(entry));
    }
}
