package com.enterprise.risk.storage.repository;

import com.enterprise.risk.storage.entity.ModelInferenceLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ModelInferenceLogRepository extends JpaRepository<ModelInferenceLogEntity, Long> {

    /**
     * 根据模型ID查询推理日志（分页）
     *
     * @param modelId  模型ID
     * @param pageable 分页参数
     * @return 推理日志分页
     */
    Page<ModelInferenceLogEntity> findByModelIdOrderByInferenceTimeDesc(String modelId, Pageable pageable);

    /**
     * 根据模型名称和版本查询推理日志
     *
     * @param modelName    模型名称
     * @param modelVersion 模型版本
     * @param pageable     分页参数
     * @return 推理日志分页
     */
    Page<ModelInferenceLogEntity> findByModelNameAndModelVersionOrderByInferenceTimeDesc(
            String modelName, String modelVersion, Pageable pageable);

    /**
     * 根据事件ID查询推理日志
     *
     * @param eventId 事件ID
     * @return 推理日志列表
     */
    List<ModelInferenceLogEntity> findByEventIdOrderByInferenceTimeDesc(String eventId);

    /**
     * 根据实体查询推理日志
     *
     * @param entityId   实体ID
     * @param entityType 实体类型
     * @param pageable   分页参数
     * @return 推理日志分页
     */
    Page<ModelInferenceLogEntity> findByEntityIdAndEntityTypeOrderByInferenceTimeDesc(
            String entityId, String entityType, Pageable pageable);

    /**
     * 按时间范围和业务线查询推理日志
     *
     * @param startTime    开始时间
     * @param endTime      结束时间
     * @param businessLine 业务线
     * @param pageable     分页参数
     * @return 推理日志分页
     */
    @Query("SELECT m FROM ModelInferenceLogEntity m WHERE " +
            "m.inferenceTime BETWEEN :startTime AND :endTime " +
            "AND (:businessLine IS NULL OR m.businessLine = :businessLine) " +
            "ORDER BY m.inferenceTime DESC")
    Page<ModelInferenceLogEntity> findByTimeRangeAndBusinessLine(
            @Param("startTime") Long startTime,
            @Param("endTime") Long endTime,
            @Param("businessLine") String businessLine,
            Pageable pageable);

    /**
     * 统计指定模型在时间范围内的推理次数
     *
     * @param modelId   模型ID
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 推理次数
     */
    @Query("SELECT COUNT(m) FROM ModelInferenceLogEntity m WHERE " +
            "m.modelId = :modelId AND m.inferenceTime BETWEEN :startTime AND :endTime")
    long countByModelIdAndTimeRange(
            @Param("modelId") String modelId,
            @Param("startTime") Long startTime,
            @Param("endTime") Long endTime);

    /**
     * 统计指定模型在时间范围内检测到异常的次数
     *
     * @param modelId   模型ID
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 异常检测次数
     */
    @Query("SELECT COUNT(m) FROM ModelInferenceLogEntity m WHERE " +
            "m.modelId = :modelId AND m.anomalyDetected = true " +
            "AND m.inferenceTime BETWEEN :startTime AND :endTime")
    long countAnomalyByModelIdAndTimeRange(
            @Param("modelId") String modelId,
            @Param("startTime") Long startTime,
            @Param("endTime") Long endTime);

    /**
     * 统计指定模型在时间范围内的平均推理延迟
     *
     * @param modelId   模型ID
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 平均延迟（毫秒）
     */
    @Query("SELECT AVG(m.inferenceLatencyMs) FROM ModelInferenceLogEntity m WHERE " +
            "m.modelId = :modelId AND m.success = true " +
            "AND m.inferenceTime BETWEEN :startTime AND :endTime")
    Double calculateAverageLatency(
            @Param("modelId") String modelId,
            @Param("startTime") Long startTime,
            @Param("endTime") Long endTime);

    /**
     * 统计指定模型在时间范围内的推理成功率
     *
     * @param modelId   模型ID
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 成功率
     */
    @Query("SELECT SUM(CASE WHEN m.success = true THEN 1 ELSE 0 END) * 1.0 / COUNT(m) " +
            "FROM ModelInferenceLogEntity m WHERE " +
            "m.modelId = :modelId AND m.inferenceTime BETWEEN :startTime AND :endTime")
    Double calculateSuccessRate(
            @Param("modelId") String modelId,
            @Param("startTime") Long startTime,
            @Param("endTime") Long endTime);

    /**
     * 查询最新的推理失败日志
     *
     * @param modelId  模型ID
     * @param pageable 分页参数
     * @return 失败的推理日志
     */
    Page<ModelInferenceLogEntity> findByModelIdAndSuccessFalseOrderByInferenceTimeDesc(
            String modelId, Pageable pageable);

    /**
     * 根据事件ID和模型ID查询推理日志
     *
     * @param eventId 事件ID
     * @param modelId 模型ID
     * @return 推理日志
     */
    Optional<ModelInferenceLogEntity> findFirstByEventIdAndModelIdOrderByInferenceTimeDesc(
            String eventId, String modelId);
}
