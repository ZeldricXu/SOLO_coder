package com.datastandard.modules.config;

import com.datastandard.modules.config.dto.ConfigLoadRequest;
import com.datastandard.modules.config.dto.ConfigResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

public interface ConfigSource {

    String getSourceName();

    int getPriority();

    Mono<ConfigResponse> loadConfig(ConfigLoadRequest request);

    Mono<Map<String, ConfigResponse>> loadConfigs(ConfigLoadRequest request);

    Flux<ConfigResponse> loadAllConfigs(ConfigLoadRequest request);

    Mono<Boolean> isAvailable();

    default Mono<String> decryptValue(String encryptedValue) {
        return Mono.just(encryptedValue);
    }

    default Mono<String> encryptValue(String plainValue) {
        return Mono.just(plainValue);
    }
}
