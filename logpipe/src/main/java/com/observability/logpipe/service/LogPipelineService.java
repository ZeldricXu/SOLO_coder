package com.observability.logpipe.service;

import com.observability.common.util.IdGenerator;
import com.observability.logpipe.filter.LogFilter;
import com.observability.logpipe.model.LogEntry;
import com.observability.logpipe.model.LogPipelineConfig;
import com.observability.logpipe.parser.LogParser;
import com.observability.logpipe.router.LogRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class LogPipelineService {

    private final List<LogParser> parsers;
    private final List<LogFilter> filters;
    private final List<LogRouter> routers;

    private final Map<String, LogPipelineConfig> pipelines = new ConcurrentHashMap<>();
    private final List<LogEntry> logBuffer = Collections.synchronizedList(new ArrayList<>());

    public Mono<LogPipelineConfig> createPipeline(LogPipelineConfig config) {
        return Mono.fromCallable(() -> {
            String pipelineId = IdGenerator.generateId("pipe");
            config.setPipelineId(pipelineId);
            config.setEnabled(true);
            pipelines.put(pipelineId, config);
            log.info("Log pipeline created - pipelineId: {}, name: {}", pipelineId, config.getName());
            return config;
        });
    }

    public Mono<Void> processLog(String rawLog, String source) {
        return Mono.fromRunnable(() -> {
            LogEntry entry = new LogEntry();
            entry.setId(IdGenerator.generateId("log"));
            entry.setTimestamp(LocalDateTime.now());
            entry.setRawLog(rawLog);
            entry.setSource(source);
            entry.setLevel("INFO");
            entry.setMessage(rawLog);

            for (LogPipelineConfig pipeline : pipelines.values()) {
                if (!pipeline.isEnabled()) {
                    continue;
                }

                LogEntry parsedEntry = parseLog(entry, pipeline);
                if (filterLog(parsedEntry, pipeline)) {
                    routeLog(parsedEntry, pipeline);
                }
            }

            logBuffer.add(entry);
            if (logBuffer.size() > 10000) {
                logBuffer.remove(0);
            }
        });
    }

    public Mono<List<LogEntry>> getLogs(int limit) {
        return Mono.fromCallable(() -> {
            int size = Math.min(limit, logBuffer.size());
            return new ArrayList<>(logBuffer.subList(logBuffer.size() - size, logBuffer.size()));
        });
    }

    public Mono<Map<String, Object>> getStats() {
        return Mono.fromCallable(() -> {
            Map<String, Object> stats = new HashMap<>();
            stats.put("pipelineCount", pipelines.size());
            stats.put("bufferSize", logBuffer.size());
            stats.put("enabledPipelines", pipelines.values().stream()
                    .filter(LogPipelineConfig::isEnabled)
                    .count());
            return stats;
        });
    }

    public Mono<List<LogPipelineConfig>> listPipelines() {
        return Mono.fromCallable(() -> new ArrayList<>(pipelines.values()));
    }

    public Mono<Void> deletePipeline(String pipelineId) {
        return Mono.fromRunnable(() -> {
            pipelines.remove(pipelineId);
            log.info("Log pipeline deleted - pipelineId: {}", pipelineId);
        });
    }

    private LogEntry parseLog(LogEntry entry, LogPipelineConfig pipeline) {
        if (pipeline.getParser() == null) {
            return entry;
        }

        return parsers.stream()
                .filter(p -> p.getType().equalsIgnoreCase(pipeline.getParser().getType()))
                .findFirst()
                .map(parser -> parser.parse(entry.getRawLog(), pipeline.getParser().getConfig()))
                .orElse(entry);
    }

    private boolean filterLog(LogEntry entry, LogPipelineConfig pipeline) {
        if (pipeline.getFilters() == null || pipeline.getFilters().isEmpty()) {
            return true;
        }

        for (LogPipelineConfig.FilterConfig filterConfig : pipeline.getFilters()) {
            boolean accepted = filters.stream()
                    .filter(f -> f.getType().equalsIgnoreCase(filterConfig.getType()))
                    .findFirst()
                    .map(filter -> filter.accept(entry, filterConfig.getConfig()))
                    .orElse(true);

            if (!accepted) {
                return false;
            }
        }

        return true;
    }

    private void routeLog(LogEntry entry, LogPipelineConfig pipeline) {
        if (pipeline.getRouters() == null) {
            return;
        }

        for (LogPipelineConfig.RouterConfig routerConfig : pipeline.getRouters()) {
            routers.stream()
                    .filter(r -> r.getType().equalsIgnoreCase(routerConfig.getType()))
                    .findFirst()
                    .ifPresent(router -> {
                        try {
                            router.route(entry, routerConfig.getConfig());
                        } catch (Exception e) {
                            log.error("Router {} failed", router.getType(), e);
                        }
                    });
        }
    }
}
