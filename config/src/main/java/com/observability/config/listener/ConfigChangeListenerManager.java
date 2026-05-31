package com.observability.config.listener;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Slf4j
@Component
public class ConfigChangeListenerManager {

    private final Map<String, List<Consumer<Map<String, Object>>>> listeners = new ConcurrentHashMap<>();

    public void addListener(String namespace, Consumer<Map<String, Object>> listener) {
        listeners.computeIfAbsent(namespace, k -> new ArrayList<>()).add(listener);
    }

    public void removeListener(String namespace, Consumer<Map<String, Object>> listener) {
        List<Consumer<Map<String, Object>>> namespaceListeners = listeners.get(namespace);
        if (namespaceListeners != null) {
            namespaceListeners.remove(listener);
        }
    }

    public void notifyListeners(String namespace, Map<String, Object> config) {
        List<Consumer<Map<String, Object>>> namespaceListeners = listeners.get(namespace);
        if (namespaceListeners != null) {
            for (Consumer<Map<String, Object>> listener : namespaceListeners) {
                try {
                    listener.accept(config);
                } catch (Exception e) {
                    log.error("Config listener failed for namespace: {}", namespace, e);
                }
            }
        }
    }
}
