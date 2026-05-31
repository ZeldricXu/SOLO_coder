package com.taskflow.core.task.internal.handler;

import com.taskflow.core.task.api.TaskHandler;
import com.taskflow.core.task.spi.TaskHandlerSpi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 核心模块内置任务处理器扩展点实现
 * 内部实现，不对外暴露
 */
@Component
@RequiredArgsConstructor
public class CoreTaskHandlerSpi implements TaskHandlerSpi {

    private final DefaultTaskHandler defaultHandler;
    private final HttpCallTaskHandler httpCallHandler;
    private final ShellTaskHandler shellHandler;

    @Override
    public List<TaskHandler> getHandlers() {
        return List.of(defaultHandler, httpCallHandler, shellHandler);
    }
}
