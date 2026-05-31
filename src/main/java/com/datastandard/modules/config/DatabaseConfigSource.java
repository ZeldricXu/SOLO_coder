package com.datastandard.modules.config;

import com.datastandard.common.model.ConfigDefinition;
import com.datastandard.common.util.IdGenerator;
import com.datastandard.modules.config.dto.ConfigLoadRequest;
import com.datastandard.modules.config.dto.ConfigResponse;
import com.datastandard.modules.config.mapper.ConfigDefinitionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
public class DatabaseConfigSource implements ConfigSource {

    private static final String SOURCE_NAME = "DATABASE";
    private static final int PRIORITY = 3;

    private final ConfigDefinitionMapper configDefinitionMapper;

    public DatabaseConfigSource(ConfigDefinitionMapper configDefinitionMapper) {
        this.configDefinitionMapper = configDefinitionMapper;
    }

    @Override
    public String getSourceName() {
        return SOURCE_NAME;
    }

    @Override
    public int getPriority() {
        return PRIORITY;
    }

    @Override
    public Mono<ConfigResponse> loadConfig(ConfigLoadRequest request) {
        return Mono.fromCallable(() -> {
            try {
                String scope = request.getScope() != null ? request.getScope() : "GLOBAL";
                ConfigDefinition config;

                if (request.getVersion() != null) {
                    config = configDefinitionMapper.findByKeyAndVersion(request.getConfigKey(),
                            Integer.parseInt(request.getVersion()));
                } else {
                    config = configDefinitionMapper.findByKeyAndScope(request.getConfigKey(), scope);
                    if (config == null) {
                        config = configDefinitionMapper.findLatestByKey(request.getConfigKey());
                    }
                }

                if (config == null) {
                    log.debug("数据库中未找到配置: configKey={}, scope={}", request.getConfigKey(), scope);
                    return null;
                }

                if (!Boolean.TRUE.equals(request.getIncludeInactive()) && !Boolean.TRUE.equals(config.getIsEnabled())) {
                    return null;
                }

                ConfigResponse response = convertToResponse(config);
                response.setSource(SOURCE_NAME);
                response.setPriority(PRIORITY);

                if (Boolean.TRUE.equals(request.getDecrypt())) {
                    response.setConfigValue(decryptValue(response.getConfigValue()).block());
                }

                log.debug("从数据库加载配置成功: configKey={}", request.getConfigKey());
                return response;
            } catch (Exception e) {
                log.error("从数据库加载配置失败: configKey={}", request.getConfigKey(), e);
                return null;
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Map<String, ConfigResponse>> loadConfigs(ConfigLoadRequest request) {
        return Mono.fromCallable(() -> {
            try {
                String scope = request.getScope() != null ? request.getScope() : "GLOBAL";
                List<ConfigDefinition> configs = configDefinitionMapper.findByScope(scope);

                Map<String, ConfigResponse> result = new HashMap<>();
                for (ConfigDefinition config : configs) {
                    if (!Boolean.TRUE.equals(request.getIncludeInactive()) && !Boolean.TRUE.equals(config.getIsEnabled())) {
                        continue;
                    }
                    ConfigResponse response = convertToResponse(config);
                    response.setSource(SOURCE_NAME);
                    response.setPriority(PRIORITY);

                    if (Boolean.TRUE.equals(request.getDecrypt())) {
                        response.setConfigValue(decryptValue(response.getConfigValue()).block());
                    }

                    result.put(config.getConfigKey(), response);
                }

                log.debug("从数据库批量加载配置成功: scope={}, count={}", scope, result.size());
                return result;
            } catch (Exception e) {
                log.error("从数据库批量加载配置失败", e);
                return new HashMap<>();
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Flux<ConfigResponse> loadAllConfigs(ConfigLoadRequest request) {
        return Flux.fromIterable(() -> {
            try {
                List<ConfigDefinition> configs = configDefinitionMapper.findAllEnabled();
                return configs.stream()
                        .map(this::convertToResponse)
                        .peek(r -> {
                            r.setSource(SOURCE_NAME);
                            r.setPriority(PRIORITY);
                        })
                        .collect(Collectors.toList())
                        .iterator();
            } catch (Exception e) {
                log.error("从数据库加载所有配置失败", e);
                return List.<ConfigResponse>of().iterator();
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Boolean> isAvailable() {
        return Mono.fromCallable(() -> {
            try {
                configDefinitionMapper.selectCount(null);
                return true;
            } catch (Exception e) {
                log.warn("数据库配置源不可用", e);
                return false;
            }
        }).subscribeOn(Schedulers.boundedElastic());
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
                .createdBy(config.getCreatedBy())
                .createdAt(config.getCreatedAt())
                .updatedBy(config.getUpdatedBy())
                .updatedAt(config.getUpdatedAt())
                .build();
    }

    public Mono<ConfigDefinition> saveConfig(ConfigDefinition config, String updatedBy, String changeReason) {
        return Mono.fromCallable(() -> {
            Integer maxVersion = configDefinitionMapper.getMaxVersion(config.getConfigKey());
            int newVersion = maxVersion != null ? maxVersion + 1 : 1;

            config.setId(IdGenerator.generateId());
            config.setVersion(newVersion);
            config.setCreatedBy(updatedBy);
            config.setCreatedAt(LocalDateTime.now());
            config.setUpdatedBy(updatedBy);
            config.setUpdatedAt(LocalDateTime.now());
            if (config.getIsEnabled() == null) {
                config.setIsEnabled(true);
            }

            configDefinitionMapper.insert(config);
            log.info("配置已保存到数据库: configKey={}, version={}", config.getConfigKey(), newVersion);
            return config;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<ConfigDefinition> updateConfig(String configKey, ConfigDefinition updates, String updatedBy, String changeReason) {
        return Mono.fromCallable(() -> {
            ConfigDefinition existing = configDefinitionMapper.findLatestByKey(configKey);
            if (existing == null) {
                throw new IllegalArgumentException("配置不存在: " + configKey);
            }

            Integer maxVersion = configDefinitionMapper.getMaxVersion(configKey);
            int newVersion = maxVersion != null ? maxVersion + 1 : 1;

            ConfigDefinition newConfig = ConfigDefinition.builder()
                    .id(IdGenerator.generateId())
                    .configKey(configKey)
                    .configName(updates.getConfigName() != null ? updates.getConfigName() : existing.getConfigName())
                    .configType(updates.getConfigType() != null ? updates.getConfigType() : existing.getConfigType())
                    .configValue(updates.getConfigValue() != null ? updates.getConfigValue() : existing.getConfigValue())
                    .configSchema(updates.getConfigSchema() != null ? updates.getConfigSchema() : existing.getConfigSchema())
                    .description(updates.getDescription() != null ? updates.getDescription() : existing.getDescription())
                    .scope(updates.getScope() != null ? updates.getScope() : existing.getScope())
                    .isEnabled(updates.getIsEnabled() != null ? updates.getIsEnabled() : existing.getIsEnabled())
                    .version(newVersion)
                    .createdBy(existing.getCreatedBy())
                    .createdAt(existing.getCreatedAt())
                    .updatedBy(updatedBy)
                    .updatedAt(LocalDateTime.now())
                    .build();

            configDefinitionMapper.insert(newConfig);
            log.info("配置已更新到数据库: configKey={}, oldVersion={}, newVersion={}",
                    configKey, existing.getVersion(), newVersion);
            return newConfig;
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
