package com.datastandard.modules.config;

import com.datastandard.common.model.ConfigDefinition;
import com.datastandard.common.util.IdGenerator;
import com.datastandard.modules.config.dto.*;
import com.datastandard.modules.config.mapper.ConfigDefinitionMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DynamicConfigManager {

    private final MultiSourceConfigLoader configLoader;
    private final ConfigChangePublisher changePublisher;
    private final DatabaseConfigSource databaseConfigSource;
    private final RedisConfigSource redisConfigSource;
    private final ConfigDefinitionMapper configDefinitionMapper;
    private final Validator validator;
    private final MeterRegistry meterRegistry;

    private final Map<String, ConfigResponse> localCache;
    private final Map<String, List<ConfigHistory>> versionHistory;

    private final Counter updateCounter;
    private final Counter createCounter;
    private final Counter deleteCounter;
    private final Counter rollbackCounter;
    private final Counter encryptCounter;
    private final Counter decryptCounter;

    private final String encryptionKey;
    private static final String ENCRYPTION_ALGORITHM = "AES";
    private static final String ENCRYPTED_PREFIX = "ENC:";

    public DynamicConfigManager(MultiSourceConfigLoader configLoader,
                                ConfigChangePublisher changePublisher,
                                DatabaseConfigSource databaseConfigSource,
                                RedisConfigSource redisConfigSource,
                                ConfigDefinitionMapper configDefinitionMapper,
                                Validator validator,
                                MeterRegistry meterRegistry,
                                @Value("${config.encryption.key:datastandard-secret-key-123456}") String encryptionKey) {
        this.configLoader = configLoader;
        this.changePublisher = changePublisher;
        this.databaseConfigSource = databaseConfigSource;
        this.redisConfigSource = redisConfigSource;
        this.configDefinitionMapper = configDefinitionMapper;
        this.validator = validator;
        this.meterRegistry = meterRegistry;
        this.encryptionKey = encryptionKey;
        this.localCache = new ConcurrentHashMap<>();
        this.versionHistory = new ConcurrentHashMap<>();

        this.updateCounter = Counter.builder("config.update.count")
                .description("配置更新次数")
                .register(meterRegistry);
        this.createCounter = Counter.builder("config.create.count")
                .description("配置创建次数")
                .register(meterRegistry);
        this.deleteCounter = Counter.builder("config.delete.count")
                .description("配置删除次数")
                .register(meterRegistry);
        this.rollbackCounter = Counter.builder("config.rollback.count")
                .description("配置回滚次数")
                .register(meterRegistry);
        this.encryptCounter = Counter.builder("config.encrypt.count")
                .description("配置加密次数")
                .register(meterRegistry);
        this.decryptCounter = Counter.builder("config.decrypt.count")
                .description("配置解密次数")
                .register(meterRegistry);
    }

    public Mono<ConfigResponse> createConfig(ConfigUpdateRequest request) {
        return Mono.fromCallable(() -> {
            Timer.Sample sample = Timer.start(meterRegistry);

            try {
                var violations = validator.validate(request);
                if (!violations.isEmpty()) {
                    String errorMsg = violations.stream()
                            .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                            .reduce((a, b) -> a + "; " + b)
                            .orElse("参数校验失败");
                    throw new IllegalArgumentException(errorMsg);
                }

                ConfigDefinition existing = configDefinitionMapper.findLatestByKey(request.getConfigKey());
                if (existing != null) {
                    throw new IllegalArgumentException("配置已存在: " + request.getConfigKey());
                }

                String configValue = request.getConfigValue();
                boolean encrypted = Boolean.TRUE.equals(request.getEncrypt());
                if (encrypted) {
                    configValue = encrypt(configValue);
                }

                ConfigDefinition config = ConfigDefinition.builder()
                        .id(IdGenerator.generateId())
                        .configKey(request.getConfigKey())
                        .configName(request.getConfigName())
                        .configType(request.getConfigType() != null ? request.getConfigType() : detectType(request.getConfigValue()))
                        .configValue(configValue)
                        .configSchema(request.getConfigSchema())
                        .description(request.getDescription())
                        .scope(request.getScope() != null ? request.getScope() : "GLOBAL")
                        .isEnabled(request.getIsEnabled() != null ? request.getIsEnabled() : true)
                        .version(1)
                        .createdBy(request.getUpdatedBy())
                        .createdAt(LocalDateTime.now())
                        .updatedBy(request.getUpdatedBy())
                        .updatedAt(LocalDateTime.now())
                        .build();

                ConfigDefinition saved = databaseConfigSource.saveConfig(config, request.getUpdatedBy(),
                        request.getChangeReason()).block();

                ConfigResponse response = convertToResponse(saved);
                response.setEncrypted(encrypted);
                if (encrypted) {
                    response.setConfigValue(decrypt(configValue));
                }

                localCache.put(request.getConfigKey(), response);

                ConfigResponse finalResponse = response;
                changePublisher.publishChange(
                        request.getConfigKey(),
                        ConfigChangeEvent.ConfigChangeType.CREATED,
                        null,
                        finalResponse,
                        request.getUpdatedBy(),
                        request.getChangeReason()
                ).subscribe();

                redisConfigSource.saveConfig(response).subscribe();

                createCounter.increment();
                log.info("配置创建成功: configKey={}, createdBy={}", request.getConfigKey(), request.getUpdatedBy());
                return response;
            } finally {
                sample.stop(meterRegistry.timer("config.create.duration"));
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<ConfigResponse> updateConfig(String configKey, ConfigUpdateRequest request) {
        return Mono.fromCallable(() -> {
            Timer.Sample sample = Timer.start(meterRegistry);

            try {
                var violations = validator.validate(request);
                if (!violations.isEmpty()) {
                    String errorMsg = violations.stream()
                            .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                            .reduce((a, b) -> a + "; " + b)
                            .orElse("参数校验失败");
                    throw new IllegalArgumentException(errorMsg);
                }

                ConfigDefinition existing = configDefinitionMapper.findLatestByKey(configKey);
                if (existing == null) {
                    throw new IllegalArgumentException("配置不存在: " + configKey);
                }

                ConfigResponse oldResponse = convertToResponse(existing);
                if (Boolean.TRUE.equals(oldResponse.getEncrypted())) {
                    oldResponse.setConfigValue(decrypt(existing.getConfigValue()));
                }

                String configValue = request.getConfigValue();
                boolean encrypted = Boolean.TRUE.equals(request.getEncrypt()) ||
                        existing.getConfigValue().startsWith(ENCRYPTED_PREFIX);
                if (encrypted && !configValue.startsWith(ENCRYPTED_PREFIX)) {
                    configValue = encrypt(configValue);
                }

                ConfigDefinition updates = ConfigDefinition.builder()
                        .configName(request.getConfigName())
                        .configType(request.getConfigType())
                        .configValue(configValue)
                        .configSchema(request.getConfigSchema())
                        .description(request.getDescription())
                        .scope(request.getScope())
                        .isEnabled(request.getIsEnabled())
                        .build();

                ConfigDefinition updated = databaseConfigSource.updateConfig(
                        configKey, updates, request.getUpdatedBy(), request.getChangeReason()
                ).block();

                ConfigResponse newResponse = convertToResponse(updated);
                newResponse.setEncrypted(encrypted);
                if (encrypted) {
                    newResponse.setConfigValue(decrypt(updated.getConfigValue()));
                }

                saveVersionHistory(configKey, oldResponse, newResponse, request.getUpdatedBy(), request.getChangeReason());

                localCache.put(configKey, newResponse);

                ConfigResponse finalNewResponse = newResponse;
                changePublisher.publishChange(
                        configKey,
                        ConfigChangeEvent.ConfigChangeType.UPDATED,
                        oldResponse,
                        finalNewResponse,
                        request.getUpdatedBy(),
                        request.getChangeReason()
                ).subscribe();

                redisConfigSource.updateConfig(newResponse, java.time.Duration.ofHours(1)).subscribe();

                updateCounter.increment();
                log.info("配置更新成功: configKey={}, oldVersion={}, newVersion={}, updatedBy={}",
                        configKey, existing.getVersion(), updated.getVersion(), request.getUpdatedBy());
                return newResponse;
            } finally {
                sample.stop(meterRegistry.timer("config.update.duration"));
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<ConfigResponse> getConfig(String configKey) {
        return getConfig(configKey, true);
    }

    public Mono<ConfigResponse> getConfig(String configKey, boolean decrypt) {
        return Mono.fromCallable(() -> {
            ConfigResponse cached = localCache.get(configKey);
            if (cached != null) {
                return cached;
            }

            ConfigLoadRequest request = ConfigLoadRequest.builder()
                    .configKey(configKey)
                    .decrypt(decrypt)
                    .build();

            ConfigResponse response = configLoader.loadConfig(request).block();
            if (response != null) {
                if (Boolean.TRUE.equals(response.getEncrypted()) && decrypt) {
                    response.setConfigValue(decrypt(response.getConfigValue()));
                }
                localCache.put(configKey, response);
            }

            return response;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<ConfigResponse> getConfigByVersion(String configKey, int version) {
        return Mono.fromCallable(() -> {
            ConfigDefinition config = configDefinitionMapper.findByKeyAndVersion(configKey, version);
            if (config == null) {
                return null;
            }

            ConfigResponse response = convertToResponse(config);
            if (Boolean.TRUE.equals(response.getEncrypted())) {
                response.setConfigValue(decrypt(config.getConfigValue()));
            }
            return response;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<List<ConfigHistory>> getConfigHistory(String configKey, int limit) {
        return Mono.fromCallable(() -> {
            List<ConfigHistory> history = versionHistory.getOrDefault(configKey, new ArrayList<>());
            return history.stream()
                    .sorted(Comparator.comparing(ConfigHistory::getOperatedAt).reversed())
                    .limit(limit)
                    .collect(Collectors.toList());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<ConfigResponse> rollbackConfig(String configKey, int targetVersion, String operatedBy, String changeReason) {
        return Mono.fromCallable(() -> {
            Timer.Sample sample = Timer.start(meterRegistry);

            try {
                ConfigDefinition targetConfig = configDefinitionMapper.findByKeyAndVersion(configKey, targetVersion);
                if (targetConfig == null) {
                    throw new IllegalArgumentException("指定版本的配置不存在: " + configKey + " v" + targetVersion);
                }

                ConfigDefinition currentConfig = configDefinitionMapper.findLatestByKey(configKey);
                if (currentConfig == null) {
                    throw new IllegalArgumentException("当前配置不存在: " + configKey);
                }

                ConfigResponse oldResponse = convertToResponse(currentConfig);
                ConfigResponse targetResponse = convertToResponse(targetConfig);

                ConfigUpdateRequest rollbackRequest = ConfigUpdateRequest.builder()
                        .configKey(configKey)
                        .configName(targetConfig.getConfigName())
                        .configType(targetConfig.getConfigType())
                        .configValue(targetConfig.getConfigValue())
                        .configSchema(targetConfig.getConfigSchema())
                        .description(targetConfig.getDescription())
                        .scope(targetConfig.getScope())
                        .isEnabled(targetConfig.getIsEnabled())
                        .updatedBy(operatedBy)
                        .changeReason(changeReason != null ? changeReason : "回滚到版本 " + targetVersion)
                        .build();

                ConfigResponse rolledBack = updateConfig(configKey, rollbackRequest).block();

                List<ConfigHistory> history = versionHistory.computeIfAbsent(configKey, k -> new ArrayList<>());
                for (ConfigHistory h : history) {
                    if (h.getVersion() != null && h.getVersion() == targetVersion) {
                        h.setRollbackStatus("SUCCESS");
                        h.setRollbackAt(LocalDateTime.now());
                        h.setRollbackBy(operatedBy);
                        break;
                    }
                }

                rollbackCounter.increment();
                log.info("配置回滚成功: configKey={}, targetVersion={}, operatedBy={}",
                        configKey, targetVersion, operatedBy);

                changePublisher.publishChange(
                        configKey,
                        ConfigChangeEvent.ConfigChangeType.ROLLBACK,
                        oldResponse,
                        rolledBack,
                        operatedBy,
                        rollbackRequest.getChangeReason()
                ).subscribe();

                return rolledBack;
            } finally {
                sample.stop(meterRegistry.timer("config.rollback.duration"));
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Void> deleteConfig(String configKey, String operatedBy, String changeReason) {
        return Mono.fromCallable(() -> {
            Timer.Sample sample = Timer.start(meterRegistry);

            try {
                ConfigDefinition existing = configDefinitionMapper.findLatestByKey(configKey);
                if (existing == null) {
                    throw new IllegalArgumentException("配置不存在: " + configKey);
                }

                existing.setIsEnabled(false);
                existing.setUpdatedBy(operatedBy);
                existing.setUpdatedAt(LocalDateTime.now());
                configDefinitionMapper.updateById(existing);

                localCache.remove(configKey);

                ConfigResponse oldResponse = convertToResponse(existing);
                changePublisher.publishChange(
                        configKey,
                        ConfigChangeEvent.ConfigChangeType.DELETED,
                        oldResponse,
                        null,
                        operatedBy,
                        changeReason
                ).subscribe();

                redisConfigSource.deleteConfig(configKey).subscribe();

                deleteCounter.increment();
                log.info("配置删除成功: configKey={}, operatedBy={}", configKey, operatedBy);
                return null;
            } finally {
                sample.stop(meterRegistry.timer("config.delete.duration"));
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    public Mono<Void> enableConfig(String configKey, String operatedBy) {
        return Mono.fromCallable(() -> {
            ConfigDefinition existing = configDefinitionMapper.findLatestByKey(configKey);
            if (existing == null) {
                throw new IllegalArgumentException("配置不存在: " + configKey);
            }

            if (Boolean.TRUE.equals(existing.getIsEnabled())) {
                return null;
            }

            ConfigResponse oldResponse = convertToResponse(existing);

            existing.setIsEnabled(true);
            existing.setUpdatedBy(operatedBy);
            existing.setUpdatedAt(LocalDateTime.now());
            configDefinitionMapper.updateById(existing);

            ConfigResponse newResponse = convertToResponse(existing);
            localCache.put(configKey, newResponse);

            changePublisher.publishChange(
                    configKey,
                    ConfigChangeEvent.ConfigChangeType.ENABLED,
                    oldResponse,
                    newResponse,
                    operatedBy,
                    "启用配置"
            ).subscribe();

            redisConfigSource.updateConfig(newResponse, java.time.Duration.ofHours(1)).subscribe();

            log.info("配置已启用: configKey={}, operatedBy={}", configKey, operatedBy);
            return null;
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    public Mono<Void> disableConfig(String configKey, String operatedBy) {
        return Mono.fromCallable(() -> {
            ConfigDefinition existing = configDefinitionMapper.findLatestByKey(configKey);
            if (existing == null) {
                throw new IllegalArgumentException("配置不存在: " + configKey);
            }

            if (Boolean.FALSE.equals(existing.getIsEnabled())) {
                return null;
            }

            ConfigResponse oldResponse = convertToResponse(existing);

            existing.setIsEnabled(false);
            existing.setUpdatedBy(operatedBy);
            existing.setUpdatedAt(LocalDateTime.now());
            configDefinitionMapper.updateById(existing);

            ConfigResponse newResponse = convertToResponse(existing);
            localCache.put(configKey, newResponse);

            changePublisher.publishChange(
                    configKey,
                    ConfigChangeEvent.ConfigChangeType.DISABLED,
                    oldResponse,
                    newResponse,
                    operatedBy,
                    "禁用配置"
            ).subscribe();

            redisConfigSource.deleteConfig(configKey).subscribe();

            log.info("配置已禁用: configKey={}, operatedBy={}", configKey, operatedBy);
            return null;
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    public Mono<Map<String, ConfigResponse>> getAllConfigs() {
        return configLoader.loadConfigs(ConfigLoadRequest.builder().decrypt(true).build());
    }

    public Flux<ConfigResponse> getConfigsByScope(String scope) {
        return Mono.fromCallable(() -> {
            List<ConfigDefinition> configs = configDefinitionMapper.findByScope(scope);
            return configs.stream()
                    .map(this::convertToResponse)
                    .peek(r -> {
                        if (Boolean.TRUE.equals(r.getEncrypted())) {
                            r.setConfigValue(decrypt(r.getConfigValue()));
                        }
                    })
                    .collect(Collectors.toList());
        }).flatMapMany(Flux::fromIterable)
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Scheduled(fixedRate = 300000)
    public void refreshConfigCache() {
        log.debug("开始刷新配置缓存");
        configLoader.refreshAllConfigs()
                .doOnSuccess(v -> {
                    localCache.clear();
                    log.info("配置缓存定时刷新完成");
                    changePublisher.publishChange(
                            "*",
                            ConfigChangeEvent.ConfigChangeType.REFRESHED,
                            null,
                            null,
                            "SYSTEM",
                            "定时刷新配置缓存"
                    ).subscribe();
                })
                .doOnError(e -> log.error("配置缓存定时刷新失败", e))
                .subscribe();
    }

    @EventListener
    @Async
    public void handleConfigChange(ConfigChangeEvent event) {
        log.debug("接收到配置变更事件: configKey={}, changeType={}",
                event.getConfigKey(), event.getChangeType());

        if (!"*".equals(event.getConfigKey())) {
            localCache.remove(event.getConfigKey());
        }
    }

    public String encrypt(String plainText) {
        if (plainText == null || plainText.startsWith(ENCRYPTED_PREFIX)) {
            return plainText;
        }
        try {
            SecretKeySpec keySpec = new SecretKeySpec(
                    Arrays.copyOf(encryptionKey.getBytes(StandardCharsets.UTF_8), 16),
                    ENCRYPTION_ALGORITHM);
            Cipher cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            String result = ENCRYPTED_PREFIX + Base64.getEncoder().encodeToString(encrypted);
            encryptCounter.increment();
            return result;
        } catch (Exception e) {
            log.warn("配置加密失败，返回原始值", e);
            return plainText;
        }
    }

    public String decrypt(String encryptedText) {
        if (encryptedText == null || !encryptedText.startsWith(ENCRYPTED_PREFIX)) {
            return encryptedText;
        }
        try {
            SecretKeySpec keySpec = new SecretKeySpec(
                    Arrays.copyOf(encryptionKey.getBytes(StandardCharsets.UTF_8), 16),
                    ENCRYPTION_ALGORITHM);
            Cipher cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
            byte[] decoded = Base64.getDecoder().decode(encryptedText.substring(ENCRYPTED_PREFIX.length()));
            String result = new String(cipher.doFinal(decoded), StandardCharsets.UTF_8);
            decryptCounter.increment();
            return result;
        } catch (Exception e) {
            log.warn("配置解密失败，返回原始值", e);
            return encryptedText;
        }
    }

    public Mono<Map<String, Object>> getManagerStats() {
        return Mono.fromCallable(() -> Map.of(
                "totalCreates", createCounter.count(),
                "totalUpdates", updateCounter.count(),
                "totalDeletes", deleteCounter.count(),
                "totalRollbacks", rollbackCounter.count(),
                "totalEncrypts", encryptCounter.count(),
                "totalDecrypts", decryptCounter.count(),
                "localCacheSize", localCache.size(),
                "versionHistoryKeys", versionHistory.size()
        )).subscribeOn(Schedulers.boundedElastic());
    }

    private void saveVersionHistory(String configKey, ConfigResponse oldConfig, ConfigResponse newConfig,
                                    String operatedBy, String changeReason) {
        ConfigHistory history = ConfigHistory.builder()
                .id(IdGenerator.generateId())
                .configId(newConfig.getId())
                .configKey(configKey)
                .oldValue(oldConfig != null ? oldConfig.getConfigValue() : null)
                .newValue(newConfig.getConfigValue())
                .oldConfigType(oldConfig != null ? oldConfig.getConfigType() : null)
                .newConfigType(newConfig.getConfigType())
                .oldEnabled(oldConfig != null ? oldConfig.getIsEnabled() : null)
                .newEnabled(newConfig.getIsEnabled())
                .version(newConfig.getVersion())
                .operationType(ConfigChangeEvent.ConfigChangeType.UPDATED.name())
                .operatedBy(operatedBy)
                .operatedAt(LocalDateTime.now())
                .changeReason(changeReason)
                .oldSchema(oldConfig != null ? oldConfig.getConfigSchema() : null)
                .newSchema(newConfig.getConfigSchema())
                .source(newConfig.getSource())
                .build();

        versionHistory.computeIfAbsent(configKey, k -> new ArrayList<>()).add(history);

        List<ConfigHistory> historyList = versionHistory.get(configKey);
        if (historyList.size() > 100) {
            versionHistory.put(configKey, historyList.subList(historyList.size() - 100, historyList.size()));
        }
    }

    private ConfigResponse convertToResponse(ConfigDefinition config) {
        return ConfigResponse.builder()
                .id(config.getId())
                .configKey(config.getConfigKey())
                .configName(config.getConfigName())
                .configType(config.getConfigType())
                .configValue(config.getConfigValue())
                .configSchema(config.getConfigSchema())
                .description(config.getDescription())
                .scope(config.getScope())
                .isEnabled(config.getIsEnabled())
                .version(config.getVersion())
                .source("DATABASE")
                .priority(3)
                .encrypted(config.getConfigValue() != null && config.getConfigValue().startsWith(ENCRYPTED_PREFIX))
                .createdBy(config.getCreatedBy())
                .createdAt(config.getCreatedAt())
                .updatedBy(config.getUpdatedBy())
                .updatedAt(config.getUpdatedAt())
                .build();
    }

    private String detectType(String value) {
        if (value == null) return "STRING";
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) return "BOOLEAN";
        try {
            Long.parseLong(value);
            return "INTEGER";
        } catch (NumberFormatException e1) {
            try {
                Double.parseDouble(value);
                return "DOUBLE";
            } catch (NumberFormatException e2) {
                return "STRING";
            }
        }
    }
}
