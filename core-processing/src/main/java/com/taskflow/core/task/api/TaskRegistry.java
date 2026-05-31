package com.taskflow.core.task.api;

import java.util.Set;

/**
 * 任务处理器注册表 - 最小化接口
 * 仅定义处理器注册和查询操作
 */
public interface TaskRegistry {

    /**
     * 注册任务处理器
     * @param handler 任务处理器
     */
    void register(TaskHandler handler);

    /**
     * 获取指定类型的处理器
     * @param type 处理器类型
     * @return 任务处理器
     */
    TaskHandler getHandler(String type);

    /**
     * 获取所有已注册的处理器类型
     * @return 处理器类型集合
     */
    Set<String> getHandlerTypes();

    /**
     * 检查指定类型的处理器是否存在
     * @param type 处理器类型
     * @return 是否存在
     */
    boolean hasHandler(String type);
}
