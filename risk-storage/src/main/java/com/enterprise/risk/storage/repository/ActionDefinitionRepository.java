package com.enterprise.risk.storage.repository;

import com.enterprise.risk.common.orchestration.ActionType;
import com.enterprise.risk.storage.entity.ActionDefinitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ActionDefinitionRepository extends JpaRepository<ActionDefinitionEntity, String> {

    /**
     * 按动作类型查询
     *
     * @param actionType 动作类型
     * @return 动作定义列表
     */
    List<ActionDefinitionEntity> findByActionType(ActionType actionType);

    /**
     * 按动作类型和启用状态查询
     *
     * @param actionType 动作类型
     * @param enabled    是否启用
     * @return 动作定义列表
     */
    List<ActionDefinitionEntity> findByActionTypeAndEnabled(ActionType actionType, Boolean enabled);

    /**
     * 按业务线查询
     *
     * @param businessLine 业务线
     * @return 动作定义列表
     */
    List<ActionDefinitionEntity> findByBusinessLine(String businessLine);

    /**
     * 按业务线和启用状态查询
     *
     * @param businessLine 业务线
     * @param enabled      是否启用
     * @return 动作定义列表
     */
    List<ActionDefinitionEntity> findByBusinessLineAndEnabled(String businessLine, Boolean enabled);

    /**
     * 按业务线和动作类型查询
     *
     * @param businessLine 业务线
     * @param actionType   动作类型
     * @return 动作定义列表
     */
    List<ActionDefinitionEntity> findByBusinessLineAndActionType(String businessLine, ActionType actionType);

    /**
     * 查询所有启用的动作定义
     *
     * @return 启用的动作定义列表
     */
    List<ActionDefinitionEntity> findByEnabledTrue();

    /**
     * 按动作类型统计启用数量
     *
     * @param actionType 动作类型
     * @return 数量
     */
    long countByActionTypeAndEnabledTrue(ActionType actionType);

    /**
     * 根据动作ID和启用状态查询
     *
     * @param actionId 动作ID
     * @param enabled  是否启用
     * @return 动作定义
     */
    Optional<ActionDefinitionEntity> findByActionIdAndEnabled(String actionId, Boolean enabled);

    /**
     * 按ID列表批量查询
     *
     * @param actionIds 动作ID列表
     * @return 动作定义列表
     */
    List<ActionDefinitionEntity> findByActionIdIn(List<String> actionIds);

    /**
     * 按ID列表批量查询启用的动作
     *
     * @param actionIds 动作ID列表
     * @return 启用的动作定义列表
     */
    List<ActionDefinitionEntity> findByActionIdInAndEnabledTrue(List<String> actionIds);
}
