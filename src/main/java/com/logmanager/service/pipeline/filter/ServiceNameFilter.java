package com.logmanager.service.pipeline.filter;

import com.logmanager.domain.model.LogEntry;
import com.logmanager.service.pipeline.LogFilter;
import java.util.Set;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ServiceNameFilter implements LogFilter {
    private final Set<String> allowedServices;
    private final boolean allow;

    public static ServiceNameFilter allow(Set<String> allowedServices) {
        return new ServiceNameFilter(allowedServices, true);
    }

    public static ServiceNameFilter deny(Set<String> deniedServices) {
        return new ServiceNameFilter(deniedServices, false);
    }

    @Override
    public boolean accept(LogEntry logEntry) {
        boolean contains = allowedServices.contains(logEntry.getServiceName());
        return allow ? contains : !contains;
    }
}
