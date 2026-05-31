package com.observability.logpipe.router;

import com.observability.logpipe.model.LogEntry;

import java.util.Map;

public interface LogRouter {

    String getType();

    void route(LogEntry entry, Map<String, Object> config);
}
