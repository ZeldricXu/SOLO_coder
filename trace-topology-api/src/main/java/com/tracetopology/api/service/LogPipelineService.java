package com.tracetopology.api.service;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public interface LogPipelineService {

    void processLog(Map<String, Object> logEntry);

    void processLogs(List<Map<String, Object>> logs);

    String createPipeline(String name, List<String> filters, List<String> outputs);

    String addFilter(String pipelineId, String filterType, Map<String, Object> config);

    String addOutput(String pipelineId, String outputType, Map<String, Object> config);

    List<String> listPipelines();

    void setLogLevel(String loggerName, String level);

    String getLogLevel(String loggerName);

    void ingest(String source, Map<String, Object> logEntry);

    void ingestBatch(String source, List<Map<String, Object>> logEntries);

    void addFilter(String name, Map<String, Object> config);

    void removeFilter(String name);

    void addRouter(String name, Map<String, Object> config);

    void removeRouter(String name);

    List<Map<String, Object>> queryLogs(String query, long startTime, long endTime, int limit);

    void subscribe(String query, Consumer<Map<String, Object>> consumer);

    Map<String, Object> getPipelineStats();
}
