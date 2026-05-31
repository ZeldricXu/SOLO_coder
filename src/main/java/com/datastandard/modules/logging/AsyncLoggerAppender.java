package com.datastandard.modules.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import com.datastandard.modules.logging.dto.LogQueryRequest;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Weigher;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Component
public class AsyncLoggerAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    private static final int QUEUE_CAPACITY = 10000;
    private static final int MAX_CACHE_ENTRIES = 10000;
    private static final int MAX_LOG_SIZE_BYTES = 1024 * 1024;

    private final BlockingQueue<Map<String, Object>> logQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

    private final Cache<Long, Map<String, Object>> logCache = Caffeine.newBuilder()
            .maximumWeight(MAX_LOG_SIZE_BYTES)
            .weigher((Weigher<Long, Map<String, Object>>) (key, value) -> {
                String message = (String) value.get("message");
                return message != null ? message.length() : 100;
            })
            .expireAfterWrite(1, TimeUnit.HOURS)
            .build();

    private final Sinks.Many<Map<String, Object>> logSink = Sinks.many().multicast().onBackpressureBuffer();

    private volatile long sequenceCounter = 0;

    @PostConstruct
    public void init() {
        startProcessingThread();
    }

    private void startProcessingThread() {
        Thread processor = new Thread(this::processLogs, "async-log-processor");
        processor.setDaemon(true);
        processor.start();
        log.info("Async logger appender started");
    }

    private void processLogs() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Map<String, Object> logEntry = logQueue.poll(1, TimeUnit.SECONDS);
                if (logEntry != null) {
                    processLogEntry(logEntry);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error processing log entry", e);
            }
        }
    }

    private void processLogEntry(Map<String, Object> logEntry) {
        long sequence = sequenceCounter++;
        logEntry.put("_sequence", sequence);
        logEntry.put("_timestamp", Instant.now());

        logCache.put(sequence, logEntry);
        logSink.tryEmitNext(logEntry);
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (!isStarted()) {
            return;
        }

        try {
            Map<String, Object> logEntry = new HashMap<>();
            logEntry.put("timestamp", Instant.ofEpochMilli(event.getTimeStamp()));
            logEntry.put("level", event.getLevel().toString());
            logEntry.put("loggerName", event.getLoggerName());
            logEntry.put("threadName", event.getThreadName());
            logEntry.put("message", event.getFormattedMessage());

            if (event.getMDCPropertyMap() != null && !event.getMDCPropertyMap().isEmpty()) {
                logEntry.put("mdc", new HashMap<>(event.getMDCPropertyMap()));
            }

            if (event.getThrowableProxy() != null) {
                logEntry.put("exception", event.getThrowableProxy().getClassName());
                logEntry.put("exceptionMessage", event.getThrowableProxy().getMessage());
            }

            if (!logQueue.offer(logEntry, 100, TimeUnit.MILLISECONDS)) {
                log.warn("Log queue is full, dropping log entry: {}", event.getFormattedMessage());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Error appending log event", e);
        }
    }

    public Flux<Map<String, Object>> streamLogs() {
        return logSink.asFlux()
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Flux<Map<String, Object>> queryLogs(LogQueryRequest request) {
        return Flux.fromIterable(logCache.asMap().values())
                .filter(logEntry -> matchesFilter(logEntry, request))
                .sort(Comparator.comparingLong(e -> (Long) e.get("_sequence")))
                .skip((long) request.getPage() * request.getPageSize())
                .take(request.getPageSize());
    }

    private boolean matchesFilter(Map<String, Object> logEntry, LogQueryRequest request) {
        if (request.getKeyword() != null && !request.getKeyword().isEmpty()) {
            String message = (String) logEntry.get("message");
            if (message == null || !message.toLowerCase().contains(request.getKeyword().toLowerCase())) {
                return false;
            }
        }

        if (request.getLevel() != null && !request.getLevel().isEmpty()) {
            String level = (String) logEntry.get("level");
            if (!request.getLevel().equalsIgnoreCase(level)) {
                return false;
            }
        }

        if (request.getLoggerName() != null && !request.getLoggerName().isEmpty()) {
            String loggerName = (String) logEntry.get("loggerName");
            if (loggerName == null || !loggerName.contains(request.getLoggerName())) {
                return false;
            }
        }

        if (request.getThreadName() != null && !request.getThreadName().isEmpty()) {
            String threadName = (String) logEntry.get("threadName");
            if (threadName == null || !threadName.contains(request.getThreadName())) {
                return false;
            }
        }

        if (request.getStartTime() != null) {
            Instant timestamp = (Instant) logEntry.get("timestamp");
            if (timestamp.isBefore(request.getStartTime())) {
                return false;
            }
        }

        if (request.getEndTime() != null) {
            Instant timestamp = (Instant) logEntry.get("timestamp");
            if (timestamp.isAfter(request.getEndTime())) {
                return false;
            }
        }

        if (request.getMdcKeys() != null && !request.getMdcKeys().isEmpty()) {
            @SuppressWarnings("unchecked")
            Map<String, String> mdc = (Map<String, String>) logEntry.get("mdc");
            if (mdc == null) {
                return false;
            }
            for (String key : request.getMdcKeys()) {
                if (!mdc.containsKey(key)) {
                    return false;
                }
            }
        }

        return true;
    }

    public long getCacheSize() {
        return logCache.estimatedSize();
    }

    public int getQueueSize() {
        return logQueue.size();
    }

    public void clearCache() {
        logCache.invalidateAll();
        log.info("Log cache cleared");
    }

    public Flux<Map<String, Object>> getRecentLogs(int limit) {
        List<Map<String, Object>> logs = new ArrayList<>(logCache.asMap().values());
        logs.sort(Comparator.comparingLong(e -> (Long) e.get("_sequence")));

        if (logs.size() > limit) {
            logs = logs.subList(logs.size() - limit, logs.size());
        }

        return Flux.fromIterable(logs);
    }
}
