package com.taskflow.core.task.internal.registry;

import com.taskflow.core.task.api.TaskHandler;
import com.taskflow.core.task.api.TaskRegistry;
import com.taskflow.core.task.spi.TaskHandlerSpi;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认任务处理器注册表实现
 * 内部实现，不对外暴露
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultTaskRegistry implements TaskRegistry {

    private final Map<String, TaskHandler> handlers = new ConcurrentHashMap<>();
    private final List<TaskHandlerSpi> handlerSpis;

    @PostConstruct
    public void init() {
        for (TaskHandlerSpi spi : handlerSpis) {
            for (TaskHandler handler : spi.getHandlers()) {
                register(handler);
            }
        }
        log.info("TaskRegistry initialized with {} handlers", handlers.size());
    }

    @Override
    public void register(TaskHandler handler) {
        handlers.put(handler.getType(), handler);
        log.info("Task handler registered: {}", handler.getType());
    }

    @Override
    public TaskHandler getHandler(String type) {
        TaskHandler handler = handlers.get(type);
        if (handler == null) {
            throw new IllegalArgumentException("Unknown task handler type: " + type);
        }
        return handler;
    }

    @Override
    public Set<String> getHandlerTypes() {
        return handlers.keySet();
    }

    @Override
    public boolean hasHandler(String type) {
        return handlers.containsKey(type);
    }
}
