package com.tracetopology.core.service.impl;

import com.tracetopology.api.service.LogPipelineService;
import com.tracetopology.core.validation.ParamValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Pattern;

@Slf4j
@RequiredArgsConstructor
public class LogPipelineServiceImpl implements LogPipelineService {

    private final Map<String, LogFilter> filters = new ConcurrentHashMap<>();
    private final Map<String, LogRouter> routers = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> logBuffer = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, List<Consumer<Map<String, Object>>>> subscribers = new ConcurrentHashMap<>();

    private final int bufferSize = 10000;
    private long processedCount = 0;
    private long filteredCount = 0;
    private long errorCount = 0;

    @Override
    public void ingest(String source, Map<String, Object> logEntry) {
        ParamValidator.validateNotBlank(source, "source");
        ParamValidator.validateNotNull(logEntry, "logEntry");

        logEntry.put("_source", source);
        logEntry.put("_ingestTime", System.currentTimeMillis());

        try {
            boolean accepted = applyFilters(logEntry);
            if (!accepted) {
                filteredCount++;
                return;
            }

            enrichLog(logEntry);
            routeLog(logEntry);
            notifySubscribers(logEntry);

            synchronized (logBuffer) {
                logBuffer.add(logEntry);
                if (logBuffer.size() > bufferSize) {
                    logBuffer.remove(0);
                }
            }

            processedCount++;
        } catch (Exception e) {
            errorCount++;
            log.error("日志处理失败: source={}, error={}", source, e.getMessage(), e);
        }
    }

    @Override
    public void ingestBatch(String source, List<Map<String, Object>> logEntries) {
        ParamValidator.validateNotBlank(source, "source");
        ParamValidator.validateNotNull(logEntries, "logEntries");

        for (Map<String, Object> entry : logEntries) {
            try {
                ingest(source, entry);
            } catch (Exception e) {
                log.warn("单条日志处理失败: {}", e.getMessage());
            }
        }
    }

    @Override
    public void addFilter(String name, Map<String, Object> config) {
        ParamValidator.validateNotBlank(name, "name");
        ParamValidator.validateNotNull(config, "config");

        String type = (String) config.getOrDefault("type", "include");
        String field = (String) config.get("field");
        Object value = config.get("value");
        String pattern = (String) config.get("pattern");

        LogFilter filter = new LogFilter(name, type, field, value, pattern);
        filters.put(name, filter);
        log.info("日志过滤器已添加: name={}, config={}", name, config);
    }

    @Override
    public void removeFilter(String name) {
        ParamValidator.validateNotBlank(name, "name");
        filters.remove(name);
        log.info("日志过滤器已移除: name={}", name);
    }

    @Override
    public void addRouter(String name, Map<String, Object> config) {
        ParamValidator.validateNotBlank(name, "name");
        ParamValidator.validateNotNull(config, "config");

        String conditionField = (String) config.get("conditionField");
        Object conditionValue = config.get("conditionValue");
        @SuppressWarnings("unchecked")
        List<String> targets = (List<String>) config.getOrDefault("targets", Collections.emptyList());

        LogRouter router = new LogRouter(name, conditionField, conditionValue, targets);
        routers.put(name, router);
        log.info("日志路由器已添加: name={}, config={}", name, config);
    }

    @Override
    public void removeRouter(String name) {
        ParamValidator.validateNotBlank(name, "name");
        routers.remove(name);
        log.info("日志路由器已移除: name={}", name);
    }

    @Override
    public List<Map<String, Object>> queryLogs(String query, long startTime, long endTime, int limit) {
        ParamValidator.validatePositive(limit, "limit");

        List<Map<String, Object>> results = new ArrayList<>();
        Pattern queryPattern = query != null && !query.isEmpty() ? Pattern.compile(query) : null;

        synchronized (logBuffer) {
            for (int i = logBuffer.size() - 1; i >= 0 && results.size() < limit; i--) {
                Map<String, Object> entry = logBuffer.get(i);
                long timestamp = ((Number) entry.getOrDefault("_ingestTime", 0L)).longValue();

                if (timestamp < startTime || timestamp > endTime) {
                    continue;
                }

                if (queryPattern != null && !matchesPattern(entry, queryPattern)) {
                    continue;
                }

                results.add(entry);
            }
        }

        return results;
    }

    @Override
    public void subscribe(String query, Consumer<Map<String, Object>> consumer) {
        ParamValidator.validateNotNull(consumer, "consumer");
        subscribers.computeIfAbsent(query != null ? query : "*", k -> new CopyOnWriteArrayList<>())
                .add(consumer);
        log.info("日志订阅已添加: query={}", query);
    }

    @Override
    public Map<String, Object> getPipelineStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("processedCount", processedCount);
        stats.put("filteredCount", filteredCount);
        stats.put("errorCount", errorCount);
        stats.put("bufferSize", logBuffer.size());
        stats.put("filterCount", filters.size());
        stats.put("routerCount", routers.size());
        stats.put("subscriberCount", subscribers.values().stream().mapToInt(List::size).sum());
        return stats;
    }

    private boolean applyFilters(Map<String, Object> logEntry) {
        for (LogFilter filter : filters.values()) {
            if (!filter.accept(logEntry)) {
                return false;
            }
        }
        return true;
    }

    private void enrichLog(Map<String, Object> logEntry) {
        if (!logEntry.containsKey("id")) {
            logEntry.put("id", UUID.randomUUID().toString());
        }
        if (!logEntry.containsKey("level")) {
            logEntry.put("level", "INFO");
        }
    }

    private void routeLog(Map<String, Object> logEntry) {
        for (LogRouter router : routers.values()) {
            router.route(logEntry);
        }
    }

    private void notifySubscribers(Map<String, Object> logEntry) {
        for (Map.Entry<String, List<Consumer<Map<String, Object>>>> entry : subscribers.entrySet()) {
            String query = entry.getKey();
            List<Consumer<Map<String, Object>>> consumers = entry.getValue();

            if ("*".equals(query) || matchesPattern(logEntry, Pattern.compile(query))) {
                for (Consumer<Map<String, Object>> consumer : consumers) {
                    try {
                        consumer.accept(logEntry);
                    } catch (Exception e) {
                        log.warn("订阅者通知失败: {}", e.getMessage());
                    }
                }
            }
        }
    }

    private boolean matchesPattern(Map<String, Object> logEntry, Pattern pattern) {
        for (Object value : logEntry.values()) {
            if (value != null && pattern.matcher(value.toString()).find()) {
                return true;
            }
        }
        return false;
    }

    static class LogFilter {
        private final String name;
        private final String type;
        private final String field;
        private final Object value;
        private final Pattern pattern;

        public LogFilter(String name, String type, String field, Object value, String pattern) {
            this.name = name;
            this.type = type;
            this.field = field;
            this.value = value;
            this.pattern = pattern != null ? Pattern.compile(pattern) : null;
        }

        public boolean accept(Map<String, Object> logEntry) {
            if (field != null && !logEntry.containsKey(field)) {
                return "exclude".equals(type);
            }

            Object fieldValue = field != null ? logEntry.get(field) : logEntry;

            boolean matches = false;
            if (pattern != null && fieldValue != null) {
                matches = pattern.matcher(fieldValue.toString()).matches();
            } else if (value != null) {
                matches = value.equals(fieldValue);
            }

            return "include".equals(type) == matches;
        }
    }

    static class LogRouter {
        private final String name;
        private final String conditionField;
        private final Object conditionValue;
        private final List<String> targets;

        public LogRouter(String name, String conditionField, Object conditionValue, List<String> targets) {
            this.name = name;
            this.conditionField = conditionField;
            this.conditionValue = conditionValue;
            this.targets = targets;
        }

        public void route(Map<String, Object> logEntry) {
            boolean matches = true;
            if (conditionField != null) {
                matches = conditionValue == null || conditionValue.equals(logEntry.get(conditionField));
            }

            if (matches) {
                logEntry.put("_targets", new ArrayList<>(targets));
            }
        }
    }

    @Override
    public void processLog(Map<String, Object> logEntry) {
        ingest("api", logEntry);
    }

    @Override
    public void processLogs(List<Map<String, Object>> logs) {
        ingestBatch("api", logs);
    }

    @Override
    public String createPipeline(String name, List<String> filters, List<String> outputs) {
        ParamValidator.validateNotBlank(name, "name");
        String pipelineId = IdGenerator.generateId("pipeline");
        LogPipeline pipeline = new LogPipeline(pipelineId, name, filters, outputs);
        pipelines.put(pipelineId, pipeline);
        log.info("创建日志管道: pipelineId={}, name={}", pipelineId, name);
        return pipelineId;
    }

    @Override
    public String addFilter(String pipelineId, String filterType, Map<String, Object> config) {
        ParamValidator.validateNotBlank(pipelineId, "pipelineId");
        ParamValidator.validateNotBlank(filterType, "filterType");
        String filterId = IdGenerator.generateId("filter");
        addFilter(filterId, config);
        LogPipeline pipeline = pipelines.get(pipelineId);
        if (pipeline != null) {
            pipeline.getFilters().add(filterId);
        }
        return filterId;
    }

    @Override
    public String addOutput(String pipelineId, String outputType, Map<String, Object> config) {
        ParamValidator.validateNotBlank(pipelineId, "pipelineId");
        ParamValidator.validateNotBlank(outputType, "outputType");
        String outputId = IdGenerator.generateId("output");
        addRouter(outputId, config);
        LogPipeline pipeline = pipelines.get(pipelineId);
        if (pipeline != null) {
            pipeline.getOutputs().add(outputId);
        }
        return outputId;
    }

    @Override
    public List<String> listPipelines() {
        return new ArrayList<>(pipelines.keySet());
    }

    @Override
    public void setLogLevel(String loggerName, String level) {
        ParamValidator.validateNotBlank(loggerName, "loggerName");
        ParamValidator.validateNotBlank(level, "level");

        try {
            org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(loggerName);
            if (logger instanceof ch.qos.logback.classic.Logger) {
                ((ch.qos.logback.classic.Logger) logger)
                        .setLevel(ch.qos.logback.classic.Level.toLevel(level.toUpperCase()));
                log.info("日志级别已调整: logger={}, level={}", loggerName, level);
            }
        } catch (Exception e) {
            log.warn("设置日志级别失败: {}", e.getMessage());
        }
    }

    @Override
    public String getLogLevel(String loggerName) {
        ParamValidator.validateNotBlank(loggerName, "loggerName");

        try {
            org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(loggerName);
            if (logger instanceof ch.qos.logback.classic.Logger) {
                ch.qos.logback.classic.Level level = ((ch.qos.logback.classic.Logger) logger).getLevel();
                return level != null ? level.toString() : "INFO";
            }
        } catch (Exception e) {
            log.warn("获取日志级别失败: {}", e.getMessage());
        }
        return "INFO";
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    static class LogPipeline {
        private final String id;
        private final String name;
        private final List<String> filters;
        private final List<String> outputs;
        private final long createdAt = System.currentTimeMillis();
    }
}
