package com.enterprise.risk.storage.repository;

import com.enterprise.risk.common.alert.AlertSeverity;
import com.enterprise.risk.common.alert.AlertStatus;
import com.enterprise.risk.storage.entity.AlertRecordEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AlertRecordRepository extends JpaRepository<AlertRecordEntity, String> {

    /**
     * 根据指纹查询告警
     *
     * @param fingerprint 指纹
     * @return 告警记录
     */
    Optional<AlertRecordEntity> findByFingerprint(String fingerprint);

    /**
     * 根据指纹和状态查询告警
     *
     * @param fingerprint 指纹
     * @param status      状态
     * @return 告警记录
     */
    Optional<AlertRecordEntity> findByFingerprintAndStatus(String fingerprint, AlertStatus status);

    /**
     * 按时间范围和状态查询告警
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @param status    状态列表
     * @return 告警列表
     */
    @Query("SELECT a FROM AlertRecordEntity a WHERE a.createdAt BETWEEN :startTime AND :endTime AND a.status IN :statuses ORDER BY a.createdAt DESC")
    List<AlertRecordEntity> findByTimeRangeAndStatusIn(
            @Param("startTime") Long startTime,
            @Param("endTime") Long endTime,
            @Param("statuses") List<AlertStatus> statuses);

    /**
     * 按时间范围、状态和指纹查询告警（分页）
     *
     * @param startTime   开始时间
     * @param endTime     结束时间
     * @param statuses    状态列表（可为空表示全部）
     * @param fingerprint 指纹（可为空表示不限制）
     * @param pageable    分页参数
     * @return 告警分页
     */
    @Query("SELECT a FROM AlertRecordEntity a WHERE " +
            "a.createdAt BETWEEN :startTime AND :endTime " +
            "AND (:statuses IS NULL OR a.status IN :statuses) " +
            "AND (:fingerprint IS NULL OR a.fingerprint = :fingerprint) " +
            "ORDER BY a.createdAt DESC")
    Page<AlertRecordEntity> findByTimeRangeAndStatusAndFingerprint(
            @Param("startTime") Long startTime,
            @Param("endTime") Long endTime,
            @Param("statuses") List<AlertStatus> statuses,
            @Param("fingerprint") String fingerprint,
            Pageable pageable);

    /**
     * 根据规则ID查询告警（分页）
     *
     * @param ruleId   规则ID
     * @param pageable 分页参数
     * @return 告警分页
     */
    Page<AlertRecordEntity> findByRuleIdOrderByCreatedAtDesc(String ruleId, Pageable pageable);

    /**
     * 根据实体ID和类型查询告警
     *
     * @param entityId   实体ID
     * @param entityType 实体类型
     * @param pageable   分页参数
     * @return 告警分页
     */
    Page<AlertRecordEntity> findByEntityIdAndEntityTypeOrderByCreatedAtDesc(
            String entityId, String entityType, Pageable pageable);

    /**
     * 按业务线查询告警
     *
     * @param businessLine 业务线
     * @param pageable     分页参数
     * @return 告警分页
     */
    Page<AlertRecordEntity> findByBusinessLineOrderByCreatedAtDesc(String businessLine, Pageable pageable);

    /**
     * 按严重级别查询告警
     *
     * @param severity  严重级别
     * @param pageable  分页参数
     * @return 告警分页
     */
    Page<AlertRecordEntity> findBySeverityOrderByCreatedAtDesc(AlertSeverity severity, Pageable pageable);

    /**
     * 统计指定时间范围内指定状态的告警数量
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @param status    状态
     * @return 告警数量
     */
    @Query("SELECT COUNT(a) FROM AlertRecordEntity a WHERE a.createdAt BETWEEN :startTime AND :endTime AND a.status = :status")
    long countByTimeRangeAndStatus(
            @Param("startTime") Long startTime,
            @Param("endTime") Long endTime,
            @Param("status") AlertStatus status);

    /**
     * 根据指纹查询活跃的告警（状态为OPEN、ESCALATED等未关闭状态）
     *
     * @param fingerprint 指纹
     * @param activeStatuses 活跃状态列表
     * @return 活跃告警
     */
    @Query("SELECT a FROM AlertRecordEntity a WHERE a.fingerprint = :fingerprint AND a.status IN :activeStatuses")
    Optional<AlertRecordEntity> findActiveAlertByFingerprint(
            @Param("fingerprint") String fingerprint,
            @Param("activeStatuses") List<AlertStatus> activeStatuses);

    /**
     * 根据实体查询活跃告警数量
     *
     * @param entityId   实体ID
     * @param entityType 实体类型
     * @param activeStatuses 活跃状态列表
     * @return 活跃告警数量
     */
    @Query("SELECT COUNT(a) FROM AlertRecordEntity a WHERE a.entityId = :entityId AND a.entityType = :entityType AND a.status IN :activeStatuses")
    long countActiveAlertsByEntity(
            @Param("entityId") String entityId,
            @Param("entityType") String entityType,
            @Param("activeStatuses") List<AlertStatus> activeStatuses);
}
