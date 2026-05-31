package com.solocoder.infrastructure.adapter.logging.plugin;

import com.solocoder.infrastructure.adapter.logging.StructuredLogEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
@RequiredArgsConstructor
public class MetricsLogPlugin implements LogPlugin {

    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();

    @Override
    public void afterLog(StructuredLogEvent event) {
        Counter counter = counters.computeIfAbsent(event.getLevel(), this::createCounter);
        counter.increment();
    }

    private Counter createCounter(String level) {
        return Counter.builder("log.events.total")
                .description("Total number of log events by level")
                .tag("level", level.toLowerCase())
                .register(meterRegistry);
    }

    @Override
    public int getOrder() {
        return 100;
    }
}
