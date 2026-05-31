package com.taskflow.core.task.api;

import com.taskflow.core.task.domain.ExecutionContext;

import java.util.Map;

/**
 * 任务处理器 - 最小化接口
 * 所有任务处理器必须实现此接口
 */
public interface TaskHandler {

    /**
     * 获取处理器类型标识
     * @return 处理器类型
     */
    String getType();

    /**
     * 执行任务处理逻辑
     * @param parameters 任务参数
     * @param context 执行上下文
     * @return 处理结果
     * @throws Exception 处理异常
     */
    Object handle(Map<String, Object> parameters, ExecutionContext context) throws Exception;

    /**
     * 验证参数（可选实现）
     * @param parameters 任务参数
     * @return 参数是否有效
     */
    default boolean validate(Map<String, Object> parameters) {
        return parameters != null;
    }
}
