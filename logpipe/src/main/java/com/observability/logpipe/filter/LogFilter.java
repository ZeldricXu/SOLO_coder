package com.observability.logpipe.filter;

import com.observability.logpipe.model.LogEntry;

import java.util.Map;

public interface LogFilter {

    String getType();

    boolean accept(LogEntry entry, Map<String, Object> config);
}
