package com.datastandard.modules.config;

import com.datastandard.modules.config.dto.ConfigLoadRequest;
import com.datastandard.modules.config.dto.ConfigResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class EnvironmentConfigSource implements ConfigSource {

    private static final String SOURCE_NAME = "ENVIRONMENT";
    private static final int PRIORITY = 1;
    private static final Pattern CONFIG_PATTERN = Pattern.compile("^APP_CONFIG_(.+)$");

    private final Environment environment;

    public EnvironmentConfigSource(Environment environment) {
        this.environment = environment;
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
                String envKey = convertToEnvKey(request.getConfigKey());
                String value = System.getenv(envKey);

                if (value == null) {
                    value = environment.getProperty(request.getConfigKey());
                }

                if (value == null) {
                    log.debug("环境变量中未找到配置: configKey={}, envKey={}", request.getConfigKey(), envKey);
                    return null;
                }

                ConfigResponse response = ConfigResponse.builder()
                        .configKey(request.getConfigKey())
                        .configValue(value)
                        .configType(detectType(value))
                        .isEnabled(true)
                        .version(1)
                        .scope("ENVIRONMENT")
                        .source(SOURCE_NAME)
                        .priority(PRIORITY)
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build();

                if (Boolean.TRUE.equals(request.getDecrypt())) {
                    response.setConfigValue(decryptValue(response.getConfigValue()).block());
                }

                log.debug("从环境变量加载配置: configKey={}", request.getConfigKey());
                return response;
            } catch (Exception e) {
                log.error("从环境变量加载配置失败: configKey={}", request.getConfigKey(), e);
                return null;
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Map<String, ConfigResponse>> loadConfigs(ConfigLoadRequest request) {
        return Mono.fromCallable(() -> {
            Map<String, ConfigResponse> result = new HashMap<>();

            Map<String, String> env = System.getenv();
            for (Map.Entry<String, String> entry : env.entrySet()) {
                Matcher matcher = CONFIG_PATTERN.matcher(entry.getKey());
                if (matcher.matches()) {
                    String configKey = convertFromEnvKey(matcher.group(1));
                    ConfigResponse response = ConfigResponse.builder()
                            .configKey(configKey)
                            .configValue(entry.getValue())
                            .configType(detectType(entry.getValue()))
                            .isEnabled(true)
                            .version(1)
                            .scope("ENVIRONMENT")
                            .source(SOURCE_NAME)
                            .priority(PRIORITY)
                            .createdAt(LocalDateTime.now())
                            .updatedAt(LocalDateTime.now())
                            .build();
                    result.put(configKey, response);
                }
            }

            log.debug("从环境变量批量加载配置: count={}", result.size());
            return result;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Flux<ConfigResponse> loadAllConfigs(ConfigLoadRequest request) {
        return loadConfigs(request)
                .flatMapMany(map -> Flux.fromIterable(map.values()));
    }

    @Override
    public Mono<Boolean> isAvailable() {
        return Mono.just(true);
    }

    private String convertToEnvKey(String configKey) {
        return "APP_CONFIG_" + configKey.toUpperCase().replace('.', '_').replace('-', '_');
    }

    private String convertFromEnvKey(String envKey) {
        return envKey.toLowerCase().replace('_', '.');
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
