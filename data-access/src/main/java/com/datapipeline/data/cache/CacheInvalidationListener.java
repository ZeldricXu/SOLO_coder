package com.datapipeline.data.cache;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Slf4j
public class CacheInvalidationListener {

    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    public void register(Consumer<String> listener) {
        listeners.add(listener);
    }

    public void notifyInvalidation(String key) {
        log.debug("Notifying cache invalidation for key: {}", key);
        for (Consumer<String> listener : listeners) {
            try {
                listener.accept(key);
            } catch (Exception e) {
                log.error("Cache invalidation listener failed for key: {}", key, e);
            }
        }
    }

    public List<Consumer<String>> getListeners() {
        return new ArrayList<>(listeners);
    }

}
