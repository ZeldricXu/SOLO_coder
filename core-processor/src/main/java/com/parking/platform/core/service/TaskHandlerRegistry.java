package com.parking.platform.core.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TaskHandlerRegistry {

    private static final Logger log = LoggerFactory.getLogger(TaskHandlerRegistry.class);

    private final Map<String, TaskHandler> handlers = new ConcurrentHashMap<>();

    public void registerHandler(String taskType, TaskHandler handler) {
        handlers.put(taskType, handler);
        log.info("Registered handler for task type: {}", taskType);
    }

    public TaskHandler getHandler(String taskType) {
        return handlers.get(taskType);
    }

    public boolean hasHandler(String taskType) {
        return handlers.containsKey(taskType);
    }

    public void unregisterHandler(String taskType) {
        handlers.remove(taskType);
        log.info("Unregistered handler for task type: {}", taskType);
    }

    public interface TaskHandler {
        Map<String, Object> execute(Map<String, Object> payload, Map<String, Object> config) throws Exception;
    }
}
