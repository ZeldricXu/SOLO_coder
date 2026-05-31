package com.taskflow.core.task.internal.handler;

import com.taskflow.core.task.api.TaskHandler;
import com.taskflow.core.task.domain.ExecutionContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 默认任务处理器
 * 内部实现，不对外暴露
 */
@Slf4j
@Component
public class DefaultTaskHandler implements TaskHandler {

    @Override
    public String getType() {
        return "default";
    }

    @Override
    public Object handle(Map<String, Object> parameters, ExecutionContext context) throws Exception {
        log.info("Executing default task handler, runId: {}, params: {}", context.getRunId(), parameters);
        Thread.sleep(100);
        return parameters;
    }
}
