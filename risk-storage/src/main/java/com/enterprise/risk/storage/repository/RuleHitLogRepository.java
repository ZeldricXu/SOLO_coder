package com.enterprise.risk.storage.repository;

import com.enterprise.risk.storage.entity.RuleHitLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RuleHitLogRepository extends JpaRepository<RuleHitLogEntity, Long> {

    /**
     * 根据规则ID查询命中日志（分页）
     *
     * @param ruleId   规则ID
     * @param pageable 分页参数
     * @return 命中日志分页
     */
    Page<RuleHitLogEntity> findByRuleIdOrderByHitTimeDesc(String ruleId, Pageable pageable);

    /**
     * 根据实体查询命中日志
     *
     * @param entityId   实体ID
     * @param entityType 实体类型
     * @param pageable   分页参数
     * @return 命中日志分页
     */
    Page<RuleHitLogEntity> findByEntityIdAndEntityTypeOrderByHitTimeDesc(
            String entityId, String entityType, Pageable pageable);

    /**
     * 根据事件ID查询命中日志
     *
     * @param eventId 事件ID
     * @return 命中日志列表
     */
    List<RuleHitLogEntity> findByEventIdOrderByHitTimeDesc(String eventId);

    /**
     * 按时间范围和业务线查询命中日志
     *
     * @param startTime    开始时间
     * @param endTime      结束时间
     * @param businessLine 业务线
     * @param pageable     分页参数
     * @return 命中日志分页
     */
    @Query("SELECT h FROM RuleHitLogEntity h WHERE " +
            "h.hitTime BETWEEN :startTime AND :endTime " +
            "AND (:businessLine IS NULL OR h.businessLine = :businessLine) " +
            "ORDER BY h.hitTime DESC")
    Page<RuleHitLogEntity> findByTimeRangeAndBusinessLine(
            @Param("startTime") Long startTime,
            @Param("endTime") Long endTime,
            @Param("businessLine") String businessLine,
            Pageable pageable);

    /**
     * 统计指定时间范围内指定规则的命中次数
     *
     * @param ruleId    规则ID
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 命中次数
     */
    @Query("SELECT COUNT(h) FROM RuleHitLogEntity h WHERE h.ruleId = :ruleId AND h.hitTime BETWEEN :startTime AND :endTime")
    long countByRuleIdAndTimeRange(
            @Param("ruleId") String ruleId,
            @Param("startTime") Long startTime,
            @Param("endTime") Long endTime);

    /**
     * 统计指定实体在时间范围内的规则命中次数
     *
     * @param entityId   实体ID
     * @param entityType 实体类型
     * @param startTime  开始时间
     * @param endTime    结束时间
     * @return 命中次数
     */
    @Query("SELECT COUNT(h) FROM RuleHitLogEntity h WHERE " +
            "h.entityId = :entityId AND h.entityType = :entityType " +
            "AND h.hitTime BETWEEN :startTime AND :endTime")
    long countByEntityAndTimeRange(
            @Param("entityId") String entityId,
            @Param("entityType") String entityType,
            @Param("startTime") Long startTime,
            @Param("endTime") Long endTime);

    /**
     * 统计指定规则在时间范围内生成告警的次数
     *
     * @param ruleId    规则ID
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 生成告警的命中次数
     */
    @Query("SELECT COUNT(h) FROM RuleHitLogEntity h WHERE " +
            "h.ruleId = :ruleId AND h.alertGenerated = true " +
            "AND h.hitTime BETWEEN :startTime AND :endTime")
    long countAlertGeneratedByRuleIdAndTimeRange(
            @Param("ruleId") String ruleId,
            @Param("startTime") Long startTime,
            @Param("endTime") Long endTime);

    /**
     * 根据告警ID查询关联的命中日志
     *
     * @param alertId 告警ID
     * @return 命中日志列表
     */
    List<RuleHitLogEntity> findByAlertIdOrderByHitTimeDesc(String alertId);
}
