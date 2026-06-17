package com.enterprise.risk.orchestration.action;

import com.enterprise.risk.common.alert.AlertEvent;
import com.enterprise.risk.orchestration.core.ActionContext;

/**
 * 动作执行接口
 * 所有风控响应动作都需要实现此接口
 */
public interface Action {

    /**
     * 获取动作唯一标识
     *
     * @return 动作ID
     */
    String getActionId();

    /**
     * 获取动作名称
     *
     * @return 动作名称
     */
    String getActionName();

    /**
     * 执行动作
     *
     * @param alertEvent 告警事件
     * @param context    动作执行上下文
     * @return 执行是否成功
     */
    boolean execute(AlertEvent alertEvent, ActionContext context);
}
