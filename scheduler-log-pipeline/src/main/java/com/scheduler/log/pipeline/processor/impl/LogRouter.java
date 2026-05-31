package com.scheduler.log.pipeline.processor.impl;

import com.scheduler.log.pipeline.model.LogEntry;
import com.scheduler.log.pipeline.processor.LogProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class LogRouter implements LogProcessor {

    @Value("${log.pipeline.router.destinations:console,file}")
    private List<String> defaultDestinations;

    @Override
    public String getName() {
        return "router";
    }

    @Override
    public Mono<LogEntry> process(LogEntry entry) {
        return Mono.fromCallable(() -> {
            List<String> destinations = determineDestinations(entry);
            entry.setDestinations(destinations);

            for (String destination : destinations) {
                routeToDestination(entry, destination);
            }

            return entry;
        });
    }

    private List<String> determineDestinations(LogEntry entry) {
        List<String> destinations = new ArrayList<>(defaultDestinations);

        String level = entry.getLevel() != null ? entry.getLevel().toUpperCase() : "INFO";
        switch (level) {
            case "ERROR":
                if (!destinations.contains("error-file")) {
                    destinations.add("error-file");
                }
                destinations.add("alert");
                break;
            case "WARN":
                destinations.add("metrics");
                break;
            default:
                break;
        }

        if (entry.getLabels() != null && entry.getLabels().containsKey("audit")) {
            destinations.add("audit-log");
        }

        return destinations;
    }

    private void routeToDestination(LogEntry entry, String destination) {
        switch (destination) {
            case "console":
                log.debug("Routing to console: {}", entry.getMessage());
                break;
            case "file":
                break;
            case "error-file":
                break;
            case "alert":
                break;
            case "metrics":
                break;
            case "audit-log":
                break;
            default:
                break;
        }
    }
}
