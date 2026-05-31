package com.dynamiclog.logging.service;

import com.dynamiclog.common.entity.LogConfig;
import com.dynamiclog.common.enums.LogLevel;
import com.dynamiclog.common.exception.ResourceNotFoundException;
import com.dynamiclog.common.util.IdGenerator;
import com.dynamiclog.persistence.mapper.LogConfigMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.LogLevel as SpringLogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DynamicLogService {

    private static final EnumMap<LogLevel, SpringLogLevel> LEVEL_TO_SPRING;
    private static final Map<SpringLogLevel, LogLevel> SPRING_TO_LEVEL;

    static {
        LEVEL_TO_SPRING = new EnumMap<>(LogLevel.class);
        LEVEL_TO_SPRING.put(LogLevel.TRACE, SpringLogLevel.TRACE);
        LEVEL_TO_SPRING.put(LogLevel.DEBUG, SpringLogLevel.DEBUG);
        LEVEL_TO_SPRING.put(LogLevel.INFO, SpringLogLevel.INFO);
        LEVEL_TO_SPRING.put(LogLevel.WARN, SpringLogLevel.WARN);
        LEVEL_TO_SPRING.put(LogLevel.ERROR, SpringLogLevel.ERROR);
        LEVEL_TO_SPRING.put(LogLevel.FATAL, SpringLogLevel.ERROR);
        LEVEL_TO_SPRING.put(LogLevel.OFF, SpringLogLevel.OFF);

        SPRING_TO_LEVEL = Arrays.stream(SpringLogLevel.values())
                .collect(Collectors.toUnmodifiableMap(
                        Function.identity(),
                        springLevel -> switch (springLevel) {
                            case TRACE -> LogLevel.TRACE;
                            case DEBUG -> LogLevel.DEBUG;
                            case INFO -> LogLevel.INFO;
                            case WARN -> LogLevel.WARN;
                            case ERROR -> LogLevel.ERROR;
                            case FATAL -> LogLevel.FATAL;
                            case OFF -> LogLevel.OFF;
                        }
                ));
    }

    private final LogConfigMapper logConfigMapper;
    private final LoggingSystem loggingSystem;
    private final MeterRegistry meterRegistry;

    private final Cache<String, LogConfig> logConfigCache;
    private final Sinks.Many<LogConfig> logLevelChangeSink;
    private final Map<String, BatchingProcessor> batchingProcessors;

    private Counter logLevelChangeCounter;
    private Counter logLevelBatchCounter;
    private Timer logLevelUpdateTimer;

    public DynamicLogService(
            LogConfigMapper logConfigMapper,
            LoggingSystem loggingSystem,
            MeterRegistry meterRegistry) {
        this.logConfigMapper = logConfigMapper;
        this.loggingSystem = loggingSystem;
        this.meterRegistry = meterRegistry;
        this.logConfigCache = Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(1000)
                .build();
        this.logLevelChangeSink = Sinks.many().multicast().onBackpressureBuffer();
        this.batchingProcessors = new ConcurrentHashMap<>();
    }

    @PostConstruct
    public void initMetrics() {
        this.logLevelChangeCounter = Counter.builder("logging.level.changes")
                .description("Number of log level changes")
                .register(meterRegistry);
        this.logLevelBatchCounter = Counter.builder("logging.level.batches")
                .description("Number of batch log level operations")
                .register(meterRegistry);
        this.logLevelUpdateTimer = Timer.builder("logging.level.update.duration")
                .description("Log level update duration")
                .register(meterRegistry);
    }

    public Mono<LogConfig> setLogLevel(String loggerName, LogLevel level, String namespace, Long ttlSeconds) {
        return Mono.fromCallable(() -> {
            Timer.Sample timerSample = Timer.start(meterRegistry);
            try {
                validateLoggerExists(loggerName);
                applyLogLevel(loggerName, level);

                LogConfig config = persistLogConfig(loggerName, level, namespace, ttlSeconds);
                updateCacheAndNotify(config);

                log.info("Log level updated: logger={}, level={}, namespace={}", loggerName, level, namespace);
                return config;
            } finally {
                timerSample.stop(logLevelUpdateTimer);
            }
        });
    }

    public Mono<LogConfig> getLogConfig(String loggerName, String namespace) {
        return Mono.fromCallable(() -> {
            String cacheKey = buildCacheKey(loggerName, namespace);
            LogConfig cached = logConfigCache.getIfPresent(cacheKey);
            if (cached != null && !isExpired(cached)) {
                return cached;
            }

            LogConfig config = fetchOrCreateConfig(loggerName, namespace);
            handleExpiredConfig(config, loggerName, namespace);

            logConfigCache.put(cacheKey, config);
            return config;
        });
    }

    public Flux<LogConfig> getAllLogConfigs(String namespace) {
        return Mono.fromCallable(() -> logConfigMapper.findByNamespace(namespace))
                .flatMapMany(Flux::fromIterable)
                .filter(config -> !isExpired(config));
    }

    public Mono<Void> resetLogLevel(String loggerName, String namespace) {
        return Mono.fromRunnable(() -> {
            LogConfig config = logConfigMapper.findByLoggerNameAndNamespace(loggerName, namespace);
            if (config != null) {
                revertToOriginalLevel(loggerName);
                logConfigMapper.deleteById(config.getId());
                logConfigCache.invalidate(buildCacheKey(loggerName, namespace));
                log.info("Log level reset: logger={}, namespace={}", loggerName, namespace);
            }
        });
    }

    public Mono<List<LogConfig>> batchSetLogLevels(List<LogConfig> configs, String namespace) {
        return Flux.fromIterable(configs)
                .flatMap(config -> setLogLevel(config.getLoggerName(), config.getLevel(), namespace, config.getTtlSeconds()))
                .collectList();
    }

    public Flux<LogConfig> streamSetLogLevels(String namespace, int batchSize, Duration flushInterval) {
        String processorKey = buildProcessorKey(namespace, batchSize, flushInterval);
        return batchingProcessors.computeIfAbsent(processorKey,
                        k -> new BatchingProcessor(namespace, batchSize, flushInterval))
                .getFlux();
    }

    public Mono<BatchResult> addToBatch(String namespace, List<LogConfig> configs) {
        String defaultKey = namespace + ":default";
        BatchingProcessor processor = batchingProcessors.computeIfAbsent(defaultKey,
                k -> new BatchingProcessor(namespace, 100, Duration.ofSeconds(1)));
        return processor.addToBatch(configs);
    }

    public Flux<LogConfig> streamAllLogConfigs(String namespace, int pageSize) {
        return Flux.defer(() -> {
            List<LogConfig> allConfigs = logConfigMapper.findByNamespace(namespace).stream()
                    .filter(config -> !isExpired(config))
                    .toList();
            return Flux.fromIterable(allConfigs)
                    .buffer(pageSize)
                    .flatMapSequential(Flux::fromIterable);
        });
    }

    public Flux<LogConfig> listenLogLevelChanges() {
        return logLevelChangeSink.asFlux()
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Flux<LogConfig> batchStreamUpdate(Flux<LogConfig> inputFlux, String namespace, int batchSize) {
        logLevelBatchCounter.increment();
        return inputFlux
                .buffer(batchSize)
                .flatMapSequential(batch -> Flux.fromIterable(batch)
                        .flatMap(config -> setLogLevel(config.getLoggerName(), config.getLevel(), namespace, config.getTtlSeconds()))
                        .subscribeOn(Schedulers.parallel()), 4);
    }

    public Mono<Map<String, Object>> getProcessingStats() {
        return Mono.fromCallable(() -> Map.of(
                "totalLevelChanges", logLevelChangeCounter.count(),
                "totalBatches", logLevelBatchCounter.count(),
                "activeProcessors", batchingProcessors.size(),
                "cacheSize", logConfigCache.estimatedSize()
        ));
    }

    private void validateLoggerExists(String loggerName) {
        LoggerConfiguration existingConfig = loggingSystem.getLoggerConfiguration(loggerName);
        if (existingConfig == null) {
            Logger rootLogger = LoggerFactory.getLogger(loggerName);
            if (rootLogger == null) {
                throw new IllegalArgumentException("Logger not found: " + loggerName);
            }
        }
    }

    private void applyLogLevel(String loggerName, LogLevel level) {
        loggingSystem.setLogLevel(loggerName, convertToSpringLevel(level));
    }

    private LogConfig persistLogConfig(String loggerName, LogLevel level, String namespace, Long ttlSeconds) {
        LogConfig existing = logConfigMapper.findByLoggerNameAndNamespace(loggerName, namespace);
        if (existing != null) {
            return updateExistingConfig(existing, level, ttlSeconds);
        }
        return createNewConfig(loggerName, level, namespace, ttlSeconds);
    }

    private LogConfig updateExistingConfig(LogConfig existing, LogLevel level, Long ttlSeconds) {
        existing.setLevel(level);
        existing.setEffectiveLevel(level);
        if (ttlSeconds != null) {
            existing.setTtlSeconds(ttlSeconds);
            existing.setExpiresAt(LocalDateTime.now().plusSeconds(ttlSeconds));
        }
        logConfigMapper.updateById(existing);
        return existing;
    }

    private LogConfig createNewConfig(String loggerName, LogLevel level, String namespace, Long ttlSeconds) {
        LogConfig config = new LogConfig();
        config.setId(IdGenerator.generateId("log"));
        config.setLoggerName(loggerName);
        config.setLevel(level);
        config.setEffectiveLevel(level);
        config.setNamespace(namespace);
        config.setDynamicEnabled(true);
        if (ttlSeconds != null) {
            config.setTtlSeconds(ttlSeconds);
            config.setExpiresAt(LocalDateTime.now().plusSeconds(ttlSeconds));
        }
        logConfigMapper.insert(config);
        return config;
    }

    private void updateCacheAndNotify(LogConfig config) {
        logConfigCache.put(buildCacheKey(config.getLoggerName(), config.getNamespace()), config);
        logLevelChangeSink.tryEmitNext(config);
        logLevelChangeCounter.increment();
    }

    private LogConfig fetchOrCreateConfig(String loggerName, String namespace) {
        LogConfig config = logConfigMapper.findByLoggerNameAndNamespace(loggerName, namespace);
        if (config == null) {
            LoggerConfiguration loggerConfig = loggingSystem.getLoggerConfiguration(loggerName);
            if (loggerConfig == null) {
                throw new ResourceNotFoundException("LogConfig", loggerName);
            }
            config = new LogConfig();
            config.setLoggerName(loggerName);
            config.setEffectiveLevel(convertFromSpringLevel(loggerConfig.getEffectiveLevel()));
        }
        return config;
    }

    private void handleExpiredConfig(LogConfig config, String loggerName, String namespace) {
        if (isExpired(config)) {
            revertToOriginalLevel(loggerName);
            LoggerConfiguration current = loggingSystem.getLoggerConfiguration(loggerName);
            config.setEffectiveLevel(convertFromSpringLevel(current.getEffectiveLevel()));
            config.setLevel(null);
            config.setTtlSeconds(null);
            config.setExpiresAt(null);
        }
    }

    private void revertToOriginalLevel(String loggerName) {
        LoggerConfiguration config = loggingSystem.getLoggerConfiguration(loggerName);
        if (config != null && config.getConfiguredLevel() != null) {
            loggingSystem.setLogLevel(loggerName, config.getConfiguredLevel());
        } else {
            loggingSystem.setLogLevel(loggerName, null);
        }
    }

    private boolean isExpired(LogConfig config) {
        return config.getExpiresAt() != null && LocalDateTime.now().isAfter(config.getExpiresAt());
    }

    private String buildCacheKey(String loggerName, String namespace) {
        return namespace + ":" + loggerName;
    }

    private String buildProcessorKey(String namespace, int batchSize, Duration flushInterval) {
        return namespace + ":" + batchSize + ":" + flushInterval.toMillis();
    }

    private static SpringLogLevel convertToSpringLevel(LogLevel level) {
        SpringLogLevel springLevel = LEVEL_TO_SPRING.get(level);
        return springLevel != null ? springLevel : SpringLogLevel.INFO;
    }

    private static LogLevel convertFromSpringLevel(SpringLogLevel level) {
        if (level == null) return LogLevel.INFO;
        LogLevel logLevel = SPRING_TO_LEVEL.get(level);
        return logLevel != null ? logLevel : LogLevel.INFO;
    }

    private class BatchingProcessor {
        private final Sinks.Many<LogConfig> sink;
        private final List<LogConfig> currentBatch;
        private final String namespace;
        private final int batchSize;
        private final Duration flushInterval;
        private final ReentrantLock batchLock;
        private volatile long lastFlushTime;

        public BatchingProcessor(String namespace, int batchSize, Duration flushInterval) {
            this.namespace = namespace;
            this.batchSize = batchSize;
            this.flushInterval = flushInterval;
            this.sink = Sinks.many().multicast().onBackpressureBuffer();
            this.currentBatch = new ArrayList<>(batchSize);
            this.batchLock = new ReentrantLock();
            this.lastFlushTime = System.currentTimeMillis();
        }

        public Flux<LogConfig> getFlux() {
            return sink.asFlux();
        }

        public Mono<BatchResult> addToBatch(List<LogConfig> configs) {
            return Mono.fromCallable(() -> {
                batchLock.lock();
                try {
                    currentBatch.addAll(configs);
                    int total = currentBatch.size();
                    boolean flushed = false;

                    if (shouldFlush(total)) {
                        flushInternal();
                        flushed = true;
                    }

                    return new BatchResult(total, flushed, currentBatch.size());
                } finally {
                    batchLock.unlock();
                }
            });
        }

        private boolean shouldFlush(int currentSize) {
            return currentSize >= batchSize
                    || System.currentTimeMillis() - lastFlushTime > flushInterval.toMillis();
        }

        private void flushInternal() {
            if (currentBatch.isEmpty()) return;

            List<LogConfig> batch = new ArrayList<>(currentBatch);
            currentBatch.clear();
            lastFlushTime = System.currentTimeMillis();

            processBatch(batch);
        }

        private void processBatch(List<LogConfig> batch) {
            Flux.fromIterable(batch)
                    .flatMap(config -> setLogLevel(config.getLoggerName(), config.getLevel(), namespace, config.getTtlSeconds()))
                    .doOnNext(sink::tryEmitNext)
                    .subscribe();
        }
    }

    public record BatchResult(int totalProcessed, boolean batchFlushed, int remainingInBatch) {}
}
