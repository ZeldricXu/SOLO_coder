package com.observability.config.loader;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ConfigLoaderManager {

    private final List<ConfigLoader> configLoaders;

    public Map<String, Object> loadFromAllSources(String namespace) {
        Map<String, Object> result = new HashMap<>();

        configLoaders.stream()
                .sorted(Comparator.comparingInt(ConfigLoader::getOrder))
                .forEach(loader -> {
                    if (loader.supports(namespace)) {
                        try {
                            Map<String, Object> config = loader.load(namespace);
                            result.putAll(config);
                        } catch (Exception e) {
                            log.warn("Config loader {} failed for namespace: {}",
                                    loader.getSource(), namespace, e);
                        }
                    }
                });

        log.info("Loaded {} configs from {} sources for namespace: {}",
                result.size(), configLoaders.size(), namespace);
        return result;
    }

    public List<ConfigLoader> getAllLoaders() {
        return configLoaders;
    }
}
