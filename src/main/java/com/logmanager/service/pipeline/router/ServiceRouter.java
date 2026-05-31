package com.logmanager.service.pipeline.router;

import com.logmanager.domain.model.LogEntry;
import com.logmanager.service.pipeline.LogRouter;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ServiceRouter implements LogRouter {
    private final Map<String, String> serviceToDestination;
    private final String defaultDestination;

    @Override
    public Optional<String> route(LogEntry logEntry) {
        String serviceName = logEntry.getServiceName();
        String destination = serviceToDestination.getOrDefault(serviceName, defaultDestination);
        return Optional.ofNullable(destination);
    }
}
