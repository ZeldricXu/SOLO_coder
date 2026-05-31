package com.datastandard.modules.config;

import com.datastandard.modules.config.dto.ConfigLoadRequest;
import com.datastandard.modules.config.dto.ConfigResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class MultiSourceConfigLoader {

    private final List<ConfigSource> configSources;
    private final ConfigChangePublisher changePublisher;
    private final MeterRegistry meterRegistry;

    private final Map<String, ConfigResponse> mergedConfigCache;
    private LocalDateTime lastMergeTime;

    private final Counter loadCounter;
    private final Counter loadErrorCounter;
    private final Counter cacheHitCounter;

    private static final long CACHE_TTL_SECONDS = 300;

    public MultiSourceConfigLoader(List<ConfigSource> configSources,
                                   ConfigChangePublisher changePublisher,
                                   MeterRegistry meterRegistry) {
        this.configSources = configSources.stream()
                .sorted(Comparator.comparingInt(ConfigSource::getPriority).reversed())
                .collect(Collectors.toList());
        this.changePublisher = changePublisher;
        this.meterRegistry = meterRegistry;
        this.mergedConfigCache = new HashMap<>();

        this.loadCounter = Counter.builder("config.load.count")
                .description("配置加载次数")
                .register(meterRegistry);
        this.loadErrorCounter = Counter.builder("config.load.error.count")
                .description("配置加载错误次数")
                .register(meterRegistry);
        this.cacheHitCounter = Counter.builder("config.cache.hit.count")
                .description("配置缓存命中次数")
                .register(meterRegistry);

        log.info("多源配置加载器已初始化，配置源按优先级排序: {}",
                this.configSources.stream()
                        .map(s -> s.getSourceName() + "(P" + s.getPriority() + ")")
                        .collect(Collectors.joining(" -> ")));
    }

    public Mono<ConfigResponse> loadConfig(String configKey) {
        return loadConfig(ConfigLoadRequest.builder()
                .configKey(configKey)
                .decrypt(true)
                .build());
    }

    public Mono<ConfigResponse> loadConfig(ConfigLoadRequest request) {
        return Mono.fromCallable(() -> {
            Timer.Sample sample = Timer.start(meterRegistry);
            loadCounter.increment();

            try {
                if (shouldUseCache(request)) {
                    ConfigResponse cached = mergedConfigCache.get(request.getConfigKey());
                    if (cached != null) {
                        cacheHitCounter.increment();
                        log.debug("使用缓存配置: configKey={}", request.getConfigKey());
                        return cached;
                    }
                }

                List<ConfigSource> sources = filterSources(request);
                if (sources.isEmpty()) {
                    sources = configSources;
                }

                ConfigResponse merged = null;
                ConfigResponse highestPriority = null;

                for (ConfigSource source : sources) {
                    if (!source.isAvailable().block()) {
                        log.debug("配置源不可用，跳过: source={}", source.getSourceName());
                        continue;
                    }

                    try {
                        ConfigResponse config = source.loadConfig(request).block();
                        if (config != null) {
                            if (highestPriority == null) {
                                highestPriority = config;
                            }
                            merged = mergeConfig(merged, config);
                        }
                    } catch (Exception e) {
                        log.warn("从配置源加载失败: source={}, configKey={}",
                                source.getSourceName(), request.getConfigKey(), e);
                    }
                }

                if (merged != null) {
                    mergedConfigCache.put(request.getConfigKey(), merged);
                    log.debug("配置加载完成: configKey={}, source={}",
                            request.getConfigKey(), merged.getSource());
                } else {
                    log.debug("未找到配置: configKey={}", request.getConfigKey());
                }

                return merged;
            } catch (Exception e) {
                loadErrorCounter.increment();
                log.error("加载配置失败: configKey={}", request.getConfigKey(), e);
                return null;
            } finally {
                sample.stop(meterRegistry.timer("config.load.duration"));
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Map<String, ConfigResponse>> loadConfigs(ConfigLoadRequest request) {
        return Mono.fromCallable(() -> {
            Timer.Sample sample = Timer.start(meterRegistry);
            loadCounter.increment();

            try {
                List<ConfigSource> sources = filterSources(request);
                if (sources.isEmpty()) {
                    sources = configSources;
                }

                Map<String, ConfigResponse> merged = new HashMap<>();

                for (ConfigSource source : sources) {
                    if (!source.isAvailable().block()) {
                        log.debug("配置源不可用，跳过: source={}", source.getSourceName());
                        continue;
                    }

                    try {
                        Map<String, ConfigResponse> configs = source.loadConfigs(request).block();
                        if (configs != null) {
                            for (Map.Entry<String, ConfigResponse> entry : configs.entrySet()) {
                                ConfigResponse existing = merged.get(entry.getKey());
                                merged.put(entry.getKey(), mergeConfig(existing, entry.getValue()));
                            }
                        }
                    } catch (Exception e) {
                        log.warn("从配置源批量加载失败: source={}", source.getSourceName(), e);
                    }
                }

                mergedConfigCache.putAll(merged);
                lastMergeTime = LocalDateTime.now();

                log.debug("批量配置加载完成: count={}", merged.size());
                return merged;
            } catch (Exception e) {
                loadErrorCounter.increment();
                log.error("批量加载配置失败", e);
                return new HashMap<>();
            } finally {
                sample.stop(meterRegistry.timer("config.batch.load.duration"));
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Flux<ConfigResponse> loadAllConfigs() {
        return Flux.fromIterable(configSources)
                .filterWhen(ConfigSource::isAvailable)
                .flatMap(source -> source.loadAllConfigs(ConfigLoadRequest.builder().build())
                        .onErrorResume(e -> {
                            log.warn("从配置源加载所有配置失败: source={}", source.getSourceName(), e);
                            return Flux.empty();
                        }))
                .collectMultimap(ConfigResponse::getConfigKey)
                .flatMapMany(map -> Flux.fromIterable(map.entrySet())
                        .map(entry -> {
                            ConfigResponse merged = null;
                            for (ConfigResponse config : entry.getValue()) {
                                merged = mergeConfig(merged, config);
                            }
                            if (merged != null) {
                                mergedConfigCache.put(entry.getKey(), merged);
                            }
                            return merged;
                        })
                        .filter(Objects::nonNull))
                .doOnComplete(() -> lastMergeTime = LocalDateTime.now());
    }

    public Mono<String> getConfigValue(String configKey, String defaultValue) {
        return loadConfig(configKey)
                .map(config -> config != null ? config.getConfigValue() : defaultValue)
                .defaultIfEmpty(defaultValue);
    }

    public Mono<Integer> getConfigAsInteger(String configKey, Integer defaultValue) {
        return getConfigValue(configKey, null)
                .map(value -> {
                    try {
                        return Integer.parseInt(value);
                    } catch (Exception e) {
                        log.warn("配置值转换为整数失败: configKey={}, value={}", configKey, value);
                        return defaultValue;
                    }
                })
                .defaultIfEmpty(defaultValue);
    }

    public Mono<Boolean> getConfigAsBoolean(String configKey, Boolean defaultValue) {
        return getConfigValue(configKey, null)
                .map(value -> {
                    try {
                        return Boolean.parseBoolean(value);
                    } catch (Exception e) {
                        log.warn("配置值转换为布尔值失败: configKey={}, value={}", configKey, value);
                        return defaultValue;
                    }
                })
                .defaultIfEmpty(defaultValue);
    }

    public Mono<Double> getConfigAsDouble(String configKey, Double defaultValue) {
        return getConfigValue(configKey, null)
                .map(value -> {
                    try {
                        return Double.parseDouble(value);
                    } catch (Exception e) {
                        log.warn("配置值转换为双精度浮点数失败: configKey={}, value={}", configKey, value);
                        return defaultValue;
                    }
                })
                .defaultIfEmpty(defaultValue);
    }

    public Mono<Void> invalidateCache(String configKey) {
        return Mono.fromRunnable(() -> {
            mergedConfigCache.remove(configKey);
            log.debug("配置缓存已失效: configKey={}", configKey);
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    public Mono<Void> invalidateAllCache() {
        return Mono.fromRunnable(() -> {
            mergedConfigCache.clear();
            lastMergeTime = null;
            log.info("所有配置缓存已失效");
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    public Mono<Void> refreshAllConfigs() {
        return invalidateAllCache()
                .thenMany(loadAllConfigs())
                .then()
                .doOnSuccess(v -> log.info("所有配置已刷新"));
    }

    public List<ConfigSource> getAvailableSources() {
        return configSources;
    }

    public Mono<Map<String, Object>> getLoaderStats() {
        return Mono.fromCallable(() -> Map.of(
                "totalLoads", loadCounter.count(),
                "totalErrors", loadErrorCounter.count(),
                "cacheHits", cacheHitCounter.count(),
                "cacheSize", mergedConfigCache.size(),
                "lastMergeTime", lastMergeTime != null ? lastMergeTime.toString() : "never",
                "sources", configSources.stream()
                        .map(s -> Map.of(
                                "name", s.getSourceName(),
                                "priority", s.getPriority()
                        ))
                        .collect(Collectors.toList())
        )).subscribeOn(Schedulers.boundedElastic());
    }

    private List<ConfigSource> filterSources(ConfigLoadRequest request) {
        if (request.getSources() == null || request.getSources().isEmpty()) {
            return configSources;
        }

        return configSources.stream()
                .filter(s -> request.getSources().contains(s.getSourceName()))
                .collect(Collectors.toList());
    }

    private boolean shouldUseCache(ConfigLoadRequest request) {
        if (Boolean.FALSE.equals(request.getDecrypt())) {
            return true;
        }
        if (lastMergeTime == null) {
            return false;
        }
        return java.time.Duration.between(lastMergeTime, LocalDateTime.now()).getSeconds() < CACHE_TTL_SECONDS;
    }

    private ConfigResponse mergeConfig(ConfigResponse existing, ConfigResponse incoming) {
        if (existing == null) {
            return incoming;
        }

        if (incoming.getPriority() > existing.getPriority()) {
            return incoming;
        }

        if (incoming.getPriority().equals(existing.getPriority())) {
            existing.setConfigValue(incoming.getConfigValue());
            existing.setConfigType(incoming.getConfigType() != null ? incoming.getConfigType() : existing.getConfigType());
            existing.setIsEnabled(incoming.getIsEnabled() != null ? incoming.getIsEnabled() : existing.getIsEnabled());
            existing.setSource(incoming.getSource());
            existing.setUpdatedAt(LocalDateTime.now());
        }

        return existing;
    }
}
