package com.parking.platform.core.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

@Service
public class EventEmitter {

    private static final Logger log = LoggerFactory.getLogger(EventEmitter.class);

    private final Map<String, List<Consumer<Object>>> listeners = new ConcurrentHashMap<>();
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    public void emit(String eventName, Object eventData) {
        List<Consumer<Object>> eventListeners = listeners.get(eventName);
        if (eventListeners != null && !eventListeners.isEmpty()) {
            log.debug("Emitting event: {} to {} listeners", eventName, eventListeners.size());
            eventListeners.forEach(listener ->
                    executorService.submit(() -> {
                        try {
                            listener.accept(eventData);
                        } catch (Exception e) {
                            log.error("Listener error for event: {}", eventName, e);
                        }
                    })
            );
        }
    }

    public void registerListener(String eventName, Consumer<Object> listener) {
        listeners.computeIfAbsent(eventName, k -> new ArrayList<>()).add(listener);
        log.debug("Registered listener for event: {}", eventName);
    }

    public void removeListener(String eventName, Consumer<Object> listener) {
        List<Consumer<Object>> eventListeners = listeners.get(eventName);
        if (eventListeners != null) {
            eventListeners.remove(listener);
            log.debug("Removed listener for event: {}", eventName);
        }
    }

    public void clearListeners(String eventName) {
        listeners.remove(eventName);
        log.debug("Cleared all listeners for event: {}", eventName);
    }

    public void shutdown() {
        executorService.shutdown();
    }
}
