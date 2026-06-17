package com.enterprise.risk.storage.repository;

import com.enterprise.risk.common.rule.RuleType;
import com.enterprise.risk.storage.entity.RuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RuleRepository extends JpaRepository<RuleEntity, String> {

    /**
     * 按业务线和启用状态查询规则，按优先级排序
     *
     * @param businessLine 业务线
     * @param enabled      是否启用
     * @return 规则列表
     */
    List<RuleEntity> findByBusinessLineAndEnabledOrderByPriorityAsc(String businessLine, Boolean enabled);

    /**
     * 查询指定业务线下所有启用的规则
     *
     * @param businessLine 业务线
     * @return 启用的规则列表
     */
    @Query("SELECT r FROM RuleEntity r WHERE r.businessLine = :businessLine AND r.enabled = true ORDER BY r.priority ASC")
    List<RuleEntity> findEnabledRulesByBusinessLine(@Param("businessLine") String businessLine);

    /**
     * 按业务线、规则类型和启用状态查询
     *
     * @param businessLine 业务线
     * @param ruleType     规则类型
     * @param enabled      是否启用
     * @return 规则列表
     */
    List<RuleEntity> findByBusinessLineAndRuleTypeAndEnabledOrderByPriorityAsc(
            String businessLine, RuleType ruleType, Boolean enabled);

    /**
     * 查询所有启用的规则
     *
     * @return 启用的规则列表
     */
    List<RuleEntity> findByEnabledTrueOrderByPriorityAsc();

    /**
     * 根据规则ID查询启用的规则
     *
     * @param ruleId 规则ID
     * @return 规则（如果启用）
     */
    Optional<RuleEntity> findByRuleIdAndEnabledTrue(String ruleId);

    /**
     * 按事件类型查询适用的规则
     *
     * @param businessLine 业务线
     * @param eventType    事件类型
     * @return 规则列表
     */
    @Query(value = "SELECT r.* FROM risk_rules r " +
            "WHERE r.business_line = :businessLine " +
            "AND r.enabled = true " +
            "AND (r.event_types IS NULL OR r.event_types @> CAST(:eventType AS jsonb) OR :eventType = ANY(r.event_types)) " +
            "ORDER BY r.priority ASC",
            nativeQuery = true)
    List<RuleEntity> findRulesByBusinessLineAndEventType(
            @Param("businessLine") String businessLine,
            @Param("eventType") String eventType);

    /**
     * 统计指定业务线下启用的规则数量
     *
     * @param businessLine 业务线
     * @param enabled      是否启用
     * @return 规则数量
     */
    long countByBusinessLineAndEnabled(String businessLine, Boolean enabled);
}
