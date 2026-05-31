package com.taskplatform.config.source;

import com.taskplatform.config.ConfigSource;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class PropertiesConfigSource implements ConfigSource {

    private final ConcurrentHashMap<String, Properties> propertiesCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        loadProperties("application");
    }

    private void loadProperties(String name) {
        try {
            ClassPathResource resource = new ClassPathResource(name + ".properties");
            if (resource.exists()) {
                Properties props = new Properties();
                props.load(resource.getInputStream());
                propertiesCache.put(name, props);
            }
        } catch (IOException e) {
            log.warn("Failed to load properties: {}", name, e);
        }
    }

    @Override
    public String getName() {
        return "properties";
    }

    @Override
    public String getValue(String namespace, String key) {
        Properties props = propertiesCache.get("application");
        return props != null ? props.getProperty(namespace + "." + key) : null;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public int getPriority() {
        return 300;
    }
}
