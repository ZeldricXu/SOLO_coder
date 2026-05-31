package com.taskflow.core.task.spi;

import com.taskflow.core.task.api.TaskHandler;

import java.util.List;

/**
 * 任务处理器扩展点接口
 * 提供给外部模块注册自定义任务处理器
 */
public interface TaskHandlerSpi {

    /**
     * 获取所有自定义任务处理器
     * @return 任务处理器列表
     */
    List<TaskHandler> getHandlers();
}
