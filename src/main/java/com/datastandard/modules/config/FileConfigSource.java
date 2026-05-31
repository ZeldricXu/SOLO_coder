package com.datastandard.modules.config;

import com.datastandard.modules.config.dto.ConfigLoadRequest;
import com.datastandard.modules.config.dto.ConfigResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

@Slf4j
@Component
public class FileConfigSource implements ConfigSource {

    private static final String SOURCE_NAME = "FILE";
    private static final int PRIORITY = 2;

    private final ObjectMapper objectMapper;
    private final String configDir;
    private final String configFile;

    private Map<String, ConfigResponse> configCache;
    private LocalDateTime lastLoadTime;
    private static final long CACHE_TTL_SECONDS = 60;

    public FileConfigSource(ObjectMapper objectMapper,
                            @Value("${config.file.dir:./config}") String configDir,
                            @Value("${config.file.name:application-config.json}") String configFile) {
        this.objectMapper = objectMapper;
        this.configDir = configDir;
        this.configFile = configFile;
        this.configCache = new HashMap<>();
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
        return reloadIfNeeded()
                .flatMap(cache -> {
                    ConfigResponse response = cache.get(request.getConfigKey());
                    if (response == null) {
                        log.debug("文件中未找到配置: configKey={}", request.getConfigKey());
                        return Mono.empty();
                    }

                    ConfigResponse result = cloneResponse(response);
                    result.setSource(SOURCE_NAME);
                    result.setPriority(PRIORITY);

                    if (Boolean.TRUE.equals(request.getDecrypt())) {
                        return decryptValue(result.getConfigValue())
                                .map(decrypted -> {
                                    result.setConfigValue(decrypted);
                                    return result;
                                });
                    }

                    return Mono.just(result);
                })
                .switchIfEmpty(Mono.fromCallable(() -> loadFromProperties(request)).subscribeOn(Schedulers.boundedElastic()));
    }

    @Override
    public Mono<Map<String, ConfigResponse>> loadConfigs(ConfigLoadRequest request) {
        return reloadIfNeeded()
                .map(cache -> {
                    Map<String, ConfigResponse> result = new HashMap<>();
                    for (Map.Entry<String, ConfigResponse> entry : cache.entrySet()) {
                        ConfigResponse response = cloneResponse(entry.getValue());
                        response.setSource(SOURCE_NAME);
                        response.setPriority(PRIORITY);

                        if (Boolean.TRUE.equals(request.getDecrypt())) {
                            response.setConfigValue(decryptValue(response.getConfigValue()).block());
                        }

                        result.put(entry.getKey(), response);
                    }
                    log.debug("从文件批量加载配置成功: count={}", result.size());
                    return result;
                });
    }

    @Override
    public Flux<ConfigResponse> loadAllConfigs(ConfigLoadRequest request) {
        return reloadIfNeeded()
                .flatMapMany(cache -> Flux.fromIterable(cache.values())
                        .map(this::cloneResponse)
                        .doOnNext(r -> {
                            r.setSource(SOURCE_NAME);
                            r.setPriority(PRIORITY);
                        }));
    }

    @Override
    public Mono<Boolean> isAvailable() {
        return Mono.fromCallable(() -> {
            Path jsonPath = Paths.get(configDir, configFile);
            Path propsPath = Paths.get(configDir, "config.properties");
            return Files.exists(jsonPath) || Files.exists(propsPath);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Map<String, ConfigResponse>> reloadIfNeeded() {
        return Mono.fromCallable(() -> {
            if (lastLoadTime != null &&
                    java.time.Duration.between(lastLoadTime, LocalDateTime.now()).getSeconds() < CACHE_TTL_SECONDS) {
                return configCache;
            }

            synchronized (this) {
                if (lastLoadTime != null &&
                        java.time.Duration.between(lastLoadTime, LocalDateTime.now()).getSeconds() < CACHE_TTL_SECONDS) {
                    return configCache;
                }

                Map<String, ConfigResponse> newCache = loadFromJsonFile();
                newCache.putAll(loadFromPropertiesFile());
                configCache = newCache;
                lastLoadTime = LocalDateTime.now();
                log.info("文件配置已重新加载: count={}", configCache.size());
                return configCache;
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Map<String, ConfigResponse> loadFromJsonFile() {
        Map<String, ConfigResponse> result = new HashMap<>();
        try {
            Path path = Paths.get(configDir, configFile);
            if (!Files.exists(path)) {
                return result;
            }

            String content = Files.readString(path);
            Map<String, Object> configMap = objectMapper.readValue(content, new TypeReference<Map<String, Object>>() {});

            for (Map.Entry<String, Object> entry : configMap.entrySet()) {
                ConfigResponse response = ConfigResponse.builder()
                        .configKey(entry.getKey())
                        .configValue(entry.getValue() != null ? entry.getValue().toString() : null)
                        .configType(detectType(entry.getValue()))
                        .isEnabled(true)
                        .version(1)
                        .scope("FILE")
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build();
                result.put(entry.getKey(), response);
            }
            log.debug("从JSON文件加载配置: count={}", result.size());
        } catch (IOException e) {
            log.warn("读取JSON配置文件失败", e);
        }
        return result;
    }

    private Map<String, ConfigResponse> loadFromPropertiesFile() {
        Map<String, ConfigResponse> result = new HashMap<>();
        try {
            Path path = Paths.get(configDir, "config.properties");
            if (!Files.exists(path)) {
                return result;
            }

            Properties props = new Properties();
            props.load(Files.newInputStream(path));

            for (String key : props.stringPropertyNames()) {
                ConfigResponse response = ConfigResponse.builder()
                        .configKey(key)
                        .configValue(props.getProperty(key))
                        .configType("STRING")
                        .isEnabled(true)
                        .version(1)
                        .scope("FILE")
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build();
                result.put(key, response);
            }
            log.debug("从Properties文件加载配置: count={}", result.size());
        } catch (IOException e) {
            log.warn("读取Properties配置文件失败", e);
        }
        return result;
    }

    private ConfigResponse loadFromProperties(ConfigLoadRequest request) {
        try {
            Path path = Paths.get(configDir, "application.properties");
            if (!Files.exists(path)) {
                return null;
            }

            Properties props = new Properties();
            props.load(Files.newInputStream(path));
            String value = props.getProperty(request.getConfigKey());
            if (value == null) {
                return null;
            }

            ConfigResponse response = ConfigResponse.builder()
                    .configKey(request.getConfigKey())
                    .configValue(value)
                    .configType("STRING")
                    .isEnabled(true)
                    .version(1)
                    .scope("FILE")
                    .source(SOURCE_NAME)
                    .priority(PRIORITY)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();

            log.debug("从Spring配置文件加载配置: configKey={}", request.getConfigKey());
            return response;
        } catch (IOException e) {
            log.warn("读取Spring配置文件失败", e);
            return null;
        }
    }

    private String detectType(Object value) {
        if (value == null) return "STRING";
        if (value instanceof Boolean) return "BOOLEAN";
        if (value instanceof Integer || value instanceof Long) return "INTEGER";
        if (value instanceof Double || value instanceof Float) return "DOUBLE";
        if (value instanceof Map || value instanceof List) return "JSON";
        return "STRING";
    }

    private ConfigResponse cloneResponse(ConfigResponse source) {
        return ConfigResponse.builder()
                .id(source.getId())
                .configKey(source.getConfigKey())
                .configName(source.getConfigName())
                .configType(source.getConfigType())
                .configValue(source.getConfigValue())
                .configSchema(source.getConfigSchema())
                .description(source.getDescription())
                .scope(source.getScope())
                .isEnabled(source.getIsEnabled())
                .version(source.getVersion())
                .source(source.getSource())
                .priority(source.getPriority())
                .encrypted(source.getEncrypted())
                .createdBy(source.getCreatedBy())
                .createdAt(source.getCreatedAt())
                .updatedBy(source.getUpdatedBy())
                .updatedAt(source.getUpdatedAt())
                .tags(source.getTags())
                .build();
    }

    public Mono<Void> refreshCache() {
        return Mono.fromRunnable(() -> {
            lastLoadTime = null;
            reloadIfNeeded().subscribe();
            log.info("文件配置缓存已刷新");
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }
}
