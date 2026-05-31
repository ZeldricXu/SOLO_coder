package com.logmanager.service.pipeline;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class LogDestinationRegistry {

    private final Map<String, LogDestination> destinations = new ConcurrentHashMap<>();

    public void register(String name, LogDestination destination) {
        destinations.put(name, destination);
        log.info("Registered log destination: {}", name);
    }

    public void unregister(String name) {
        destinations.remove(name);
        log.info("Unregistered log destination: {}", name);
    }

    public LogDestination getDestination(String name) {
        return destinations.get(name);
    }

    public boolean hasDestination(String name) {
        return destinations.containsKey(name);
    }

    public int size() {
        return destinations.size();
    }
}
