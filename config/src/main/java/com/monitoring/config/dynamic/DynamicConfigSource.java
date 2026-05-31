package com.monitoring.config.dynamic;

import com.monitoring.common.model.ConfigDefinition;
import com.monitoring.config.service.ConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

@Component
@RequiredArgsConstructor
public class DynamicConfigSource {

    private final ConfigService configService;

    private final Set<ConfigChangeListener> listeners = new CopyOnWriteArraySet<>();

    public void addListener(ConfigChangeListener listener) {
        listeners.add(listener);
    }

    public void removeListener(ConfigChangeListener listener) {
        listeners.remove(listener);
    }

    public Map<String, Object> getConfig(String configId) {
        return configService.getParameters(configId);
    }

    public <T> T getProperty(String configId, String key, T defaultValue) {
        return configService.getParameterOrDefault(configId, key, defaultValue);
    }

    public void notifyConfigChanged(ConfigDefinition config) {
        listeners.forEach(listener -> {
            try {
                listener.onConfigChanged(config);
            } catch (Exception e) {
            }
        });
    }

    public interface ConfigChangeListener {
        void onConfigChanged(ConfigDefinition config);
    }
}
