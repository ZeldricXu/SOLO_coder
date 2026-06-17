package com.loganalytics.pipeline.processor;

import com.loganalytics.common.model.LogEvent;

import java.util.Map;

public interface Processor {
    String getType();

    LogEvent process(LogEvent event);

    void configure(Map<String, String> params);

    void close();
}
