package com.observability.logpipe.parser;

import com.observability.logpipe.model.LogEntry;

import java.util.Map;

public interface LogParser {

    String getType();

    LogEntry parse(String rawLog, Map<String, Object> config);
}
