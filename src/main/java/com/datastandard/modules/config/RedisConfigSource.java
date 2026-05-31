package com.datastandard.modules.config;

import com.datastandard.modules.config.dto.ConfigLoadRequest;
import com.datastandard.modules.config.dto.ConfigResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class RedisConfigSource implements ConfigSource {

    private static final String SOURCE_NAME = "REDIS";
    private static final int PRIORITY = 4;
    private static final String CONFIG_KEY_PREFIX = "config:";
    private static final Duration DEFAULT_TTL = Duration.ofHours(1);

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisConfigSource(ReactiveRedisTemplate<String, String> redisTemplate,
                             ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
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
        String redisKey = buildRedisKey(request.getConfigKey());
        return redisTemplate.opsForValue().get(redisKey)
                .flatMap(value -> {
                    try {
                        ConfigResponse response = deserialize(value);
                        response.setSource(SOURCE_NAME);
                        response.setPriority(PRIORITY);

                        if (Boolean.TRUE.equals(request.getDecrypt())) {
                            return decryptValue(response.getConfigValue())
                                    .map(decrypted -> {
                                        response.setConfigValue(decrypted);
                                        return response;
                                    });
                        }

                        log.debug("从Redis加载配置: configKey={}", request.getConfigKey());
                        return Mono.just(response);
                    } catch (Exception e) {
                        log.error("解析Redis配置失败: configKey={}", request.getConfigKey(), e);
                        return Mono.empty();
                    }
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.debug("Redis中未找到配置: configKey={}", request.getConfigKey());
                    return Mono.empty();
                }))
                .onErrorResume(e -> {
                    log.warn("从Redis加载配置失败: configKey={}", request.getConfigKey(), e);
                    return Mono.empty();
                });
    }

    @Override
    public Mono<Map<String, ConfigResponse>> loadConfigs(ConfigLoadRequest request) {
        if (request.getConfigKeys() == null || request.getConfigKeys().isEmpty()) {
            return Flux.fromIterable(System.getenv().keySet())
                    .filter(k -> k.startsWith(CONFIG_KEY_PREFIX))
                    .collectList()
                    .flatMap(keys -> redisTemplate.opsForValue().multiGet(keys))
                    .flatMap(values -> {
                        Map<String, ConfigResponse> result = new HashMap<>();
                        if (values == null) return Mono.just(result);

                        for (int i = 0; i < values.size(); i++) {
                            String value = values.get(i);
                            if (value != null) {
                                try {
                                    ConfigResponse response = deserialize(value);
                                    response.setSource(SOURCE_NAME);
                                    response.setPriority(PRIORITY);
                                    result.put(response.getConfigKey(), response);
                                } catch (Exception e) {
                                    log.warn("解析Redis配置失败", e);
                                }
                            }
                        }
                        return Mono.just(result);
                    });
        }

        return Flux.fromIterable(request.getConfigKeys())
                .flatMap(key -> {
                    ConfigLoadRequest singleRequest = ConfigLoadRequest.builder()
                            .configKey(key)
                            .decrypt(request.getDecrypt())
                            .build();
                    return loadConfig(singleRequest)
                            .map(response -> Map.entry(key, response));
                })
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    @Override
    public Flux<ConfigResponse> loadAllConfigs(ConfigLoadRequest request) {
        return redisTemplate.keys(CONFIG_KEY_PREFIX + "*")
                .flatMap(redisTemplate.opsForValue()::get)
                .flatMap(value -> {
                    try {
                        ConfigResponse response = deserialize(value);
                        response.setSource(SOURCE_NAME);
                        response.setPriority(PRIORITY);
                        return Mono.just(response);
                    } catch (Exception e) {
                        log.warn("解析Redis配置失败", e);
                        return Mono.empty();
                    }
                });
    }

    @Override
    public Mono<Boolean> isAvailable() {
        return redisTemplate.hasKey(CONFIG_KEY_PREFIX + "health_check")
                .map(Boolean.TRUE::equals)
                .switchIfEmpty(Mono.just(true))
                .onErrorResume(e -> {
                    log.warn("Redis配置源不可用", e);
                    return Mono.just(false);
                });
    }

    public Mono<Boolean> saveConfig(ConfigResponse config) {
        String redisKey = buildRedisKey(config.getConfigKey());
        try {
            String value = serialize(config);
            return redisTemplate.opsForValue().set(redisKey, value, DEFAULT_TTL)
                    .doOnSuccess(success -> {
                        if (Boolean.TRUE.equals(success)) {
                            log.info("配置已保存到Redis: configKey={}", config.getConfigKey());
                        }
                    })
                    .onErrorResume(e -> {
                        log.error("保存配置到Redis失败: configKey={}", config.getConfigKey(), e);
                        return Mono.just(false);
                    });
        } catch (JsonProcessingException e) {
            log.error("序列化配置失败: configKey={}", config.getConfigKey(), e);
            return Mono.just(false);
        }
    }

    public Mono<Boolean> deleteConfig(String configKey) {
        String redisKey = buildRedisKey(configKey);
        return redisTemplate.delete(redisKey)
                .map(deleted -> deleted > 0)
                .doOnSuccess(success -> {
                    if (Boolean.TRUE.equals(success)) {
                        log.info("配置已从Redis删除: configKey={}", configKey);
                    }
                })
                .onErrorResume(e -> {
                    log.error("从Redis删除配置失败: configKey={}", configKey, e);
                    return Mono.just(false);
                });
    }

    public Mono<Boolean> updateConfig(ConfigResponse config, Duration ttl) {
        String redisKey = buildRedisKey(config.getConfigKey());
        try {
            String value = serialize(config);
            return redisTemplate.opsForValue().set(redisKey, value, ttl)
                    .doOnSuccess(success -> {
                        if (Boolean.TRUE.equals(success)) {
                            log.info("配置已更新到Redis: configKey={}, ttl={}s", config.getConfigKey(), ttl.getSeconds());
                        }
                    })
                    .onErrorResume(e -> {
                        log.error("更新配置到Redis失败: configKey={}", config.getConfigKey(), e);
                        return Mono.just(false);
                    });
        } catch (JsonProcessingException e) {
            log.error("序列化配置失败: configKey={}", config.getConfigKey(), e);
            return Mono.just(false);
        }
    }

    public Mono<Boolean> evictConfig(String configKey) {
        String redisKey = buildRedisKey(configKey);
        return redisTemplate.expire(redisKey, Duration.ZERO)
                .onErrorResume(e -> {
                    log.error("驱逐Redis配置失败: configKey={}", configKey, e);
                    return Mono.just(false);
                });
    }

    private String buildRedisKey(String configKey) {
        return CONFIG_KEY_PREFIX + configKey;
    }

    private String serialize(ConfigResponse config) throws JsonProcessingException {
        Map<String, Object> data = new HashMap<>();
        data.put("id", config.getId());
        data.put("configKey", config.getConfigKey());
        data.put("configName", config.getConfigName());
        data.put("configType", config.getConfigType());
        data.put("configValue", config.getConfigValue());
        data.put("configSchema", config.getConfigSchema());
        data.put("description", config.getDescription());
        data.put("scope", config.getScope());
        data.put("isEnabled", config.getIsEnabled());
        data.put("version", config.getVersion());
        data.put("encrypted", config.getEncrypted());
        data.put("updatedAt", config.getUpdatedAt() != null ? config.getUpdatedAt().toString() : LocalDateTime.now().toString());
        return objectMapper.writeValueAsString(data);
    }

    private ConfigResponse deserialize(String json) throws JsonProcessingException {
        Map<String, Object> data = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        return ConfigResponse.builder()
                .id(data.get("id") != null ? Long.valueOf(data.get("id").toString()) : null)
                .configKey((String) data.get("configKey"))
                .configName((String) data.get("configName"))
                .configType((String) data.get("configType"))
                .configValue((String) data.get("configValue"))
                .configSchema(data.get("configSchema") != null ?
                        objectMapper.convertValue(data.get("configSchema"), new TypeReference<Map<String, Object>>() {}) : null)
                .description((String) data.get("description"))
                .scope((String) data.get("scope"))
                .isEnabled(data.get("isEnabled") != null ? (Boolean) data.get("isEnabled") : true)
                .version(data.get("version") != null ? Integer.valueOf(data.get("version").toString()) : 1)
                .encrypted(data.get("encrypted") != null ? (Boolean) data.get("encrypted") : false)
                .createdAt(data.get("createdAt") != null ? LocalDateTime.parse(data.get("createdAt").toString()) : LocalDateTime.now())
                .updatedAt(data.get("updatedAt") != null ? LocalDateTime.parse(data.get("updatedAt").toString()) : LocalDateTime.now())
                .build();
    }
}
