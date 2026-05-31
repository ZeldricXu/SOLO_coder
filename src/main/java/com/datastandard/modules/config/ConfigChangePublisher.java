package com.datastandard.modules.config;

import com.datastandard.common.util.IdGenerator;
import com.datastandard.modules.config.dto.ConfigHistory;
import com.datastandard.modules.config.dto.ConfigResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Slf4j
@Component
public class ConfigChangePublisher {

    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;

    private final Map<String, List<Consumer<ConfigChangeEvent>>> listeners = new ConcurrentHashMap<>();
    private final List<ConfigHistory> changeHistory = new CopyOnWriteArrayList<>();

    private final Counter changePublishedCounter;
    private final Counter changeConsumedCounter;
    private final Counter changeFailedCounter;

    private static final int MAX_HISTORY_SIZE = 1000;

    public ConfigChangePublisher(ApplicationEventPublisher eventPublisher, MeterRegistry meterRegistry) {
        this.eventPublisher = eventPublisher;
        this.meterRegistry = meterRegistry;

        this.changePublishedCounter = Counter.builder("config.change.published.count")
                .description("已发布的配置变更事件数量")
                .register(meterRegistry);
        this.changeConsumedCounter = Counter.builder("config.change.consumed.count")
                .description("已消费的配置变更事件数量")
                .register(meterRegistry);
        this.changeFailedCounter = Counter.builder("config.change.failed.count")
                .description("失败的配置变更事件数量")
                .register(meterRegistry);
    }

    public Mono<Void> publishChange(String configKey, ConfigChangeEvent.ConfigChangeType changeType,
                                    ConfigResponse oldConfig, ConfigResponse newConfig,
                                    String operatedBy, String changeReason) {
        return Mono.fromCallable(() -> {
            ConfigChangeEvent event = new ConfigChangeEvent(this, configKey, changeType, oldConfig, newConfig);
            event.setOperatedBy(operatedBy);
            event.setChangeReason(changeReason);
            event.setSource(oldConfig != null ? oldConfig.getSource() :
                    (newConfig != null ? newConfig.getSource() : "UNKNOWN"));

            ConfigHistory history = ConfigHistory.builder()
                    .id(IdGenerator.generateId())
                    .configId(newConfig != null ? newConfig.getId() : (oldConfig != null ? oldConfig.getId() : null))
                    .configKey(configKey)
                    .oldValue(oldConfig != null ? oldConfig.getConfigValue() : null)
                    .newValue(newConfig != null ? newConfig.getConfigValue() : null)
                    .oldConfigType(oldConfig != null ? oldConfig.getConfigType() : null)
                    .newConfigType(newConfig != null ? newConfig.getConfigType() : null)
                    .oldEnabled(oldConfig != null ? oldConfig.getIsEnabled() : null)
                    .newEnabled(newConfig != null ? newConfig.getIsEnabled() : null)
                    .version(newConfig != null ? newConfig.getVersion() : (oldConfig != null ? oldConfig.getVersion() : null))
                    .operationType(changeType.name())
                    .operatedBy(operatedBy)
                    .operatedAt(LocalDateTime.now())
                    .changeReason(changeReason)
                    .oldSchema(oldConfig != null ? oldConfig.getConfigSchema() : null)
                    .newSchema(newConfig != null ? newConfig.getConfigSchema() : null)
                    .source(event.getSource())
                    .build();
            event.setHistory(history);

            saveHistory(history);

            eventPublisher.publishEvent(event);
            notifyListeners(event);

            changePublishedCounter.increment();
            log.info("配置变更事件已发布: configKey={}, changeType={}, operatedBy={}",
                    configKey, changeType, operatedBy);

            return null;
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    public Mono<Void> publishBatchChanges(List<ConfigChangeEvent> events) {
        return Flux.fromIterable(events)
                .flatMap(event -> publishChange(
                        event.getConfigKey(),
                        event.getChangeType(),
                        event.getOldConfig(),
                        event.getNewConfig(),
                        event.getOperatedBy(),
                        event.getChangeReason()
                ))
                .then()
                .doOnSuccess(v -> log.info("批量配置变更事件已发布: count={}", events.size()));
    }

    public void registerListener(String configKey, Consumer<ConfigChangeEvent> listener) {
        listeners.computeIfAbsent(configKey, k -> new CopyOnWriteArrayList<>())
                .add(listener);
        log.debug("已注册配置变更监听器: configKey={}", configKey);
    }

    public void unregisterListener(String configKey, Consumer<ConfigChangeEvent> listener) {
        List<Consumer<ConfigChangeEvent>> configListeners = listeners.get(configKey);
        if (configListeners != null) {
            configListeners.remove(listener);
            log.debug("已移除配置变更监听器: configKey={}", configKey);
        }
    }

    public void registerGlobalListener(Consumer<ConfigChangeEvent> listener) {
        registerListener("*", listener);
        log.debug("已注册全局配置变更监听器");
    }

    @Async
    public void notifyListeners(ConfigChangeEvent event) {
        try {
            List<Consumer<ConfigChangeEvent>> globalListeners = listeners.getOrDefault("*", new ArrayList<>());
            for (Consumer<ConfigChangeEvent> listener : globalListeners) {
                try {
                    listener.accept(event);
                    changeConsumedCounter.increment();
                } catch (Exception e) {
                    changeFailedCounter.increment();
                    log.error("全局配置变更监听器执行失败: eventId={}", event.getEventId(), e);
                }
            }

            List<Consumer<ConfigChangeEvent>> keyListeners = listeners.getOrDefault(event.getConfigKey(), new ArrayList<>());
            for (Consumer<ConfigChangeEvent> listener : keyListeners) {
                try {
                    listener.accept(event);
                    changeConsumedCounter.increment();
                } catch (Exception e) {
                    changeFailedCounter.increment();
                    log.error("配置变更监听器执行失败: configKey={}, eventId={}",
                            event.getConfigKey(), event.getEventId(), e);
                }
            }
        } catch (Exception e) {
            log.error("通知配置变更监听器失败", e);
        }
    }

    private void saveHistory(ConfigHistory history) {
        changeHistory.add(history);
        while (changeHistory.size() > MAX_HISTORY_SIZE) {
            changeHistory.remove(0);
        }
    }

    public Mono<List<ConfigHistory>> getChangeHistory(String configKey, int limit) {
        return Mono.fromCallable(() -> {
            List<ConfigHistory> result = new ArrayList<>();
            for (int i = changeHistory.size() - 1; i >= 0 && result.size() < limit; i--) {
                ConfigHistory history = changeHistory.get(i);
                if (configKey == null || configKey.equals(history.getConfigKey())) {
                    result.add(history);
                }
            }
            return result;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Map<String, Object>> getPublisherStats() {
        return Mono.fromCallable(() -> Map.of(
                "totalPublished", changePublishedCounter.count(),
                "totalConsumed", changeConsumedCounter.count(),
                "totalFailed", changeFailedCounter.count(),
                "registeredListeners", listeners.size(),
                "historySize", changeHistory.size()
        )).subscribeOn(Schedulers.boundedElastic());
    }
}
