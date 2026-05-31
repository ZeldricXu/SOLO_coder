package com.datastandard.modules.anomaly.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datastandard.common.model.AnomalyDetectionResult;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface AnomalyDetectionResultMapper extends BaseMapper<AnomalyDetectionResult> {

    @Select("SELECT * FROM anomaly_detection_result WHERE metric_code = #{metricCode} AND detected_at BETWEEN #{startTime} AND #{endTime}")
    List<AnomalyDetectionResult> findByMetricAndTimeRange(
            @Param("metricCode") String metricCode,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    @Select("SELECT * FROM anomaly_detection_result WHERE entity_id = #{entityId} AND status = 'ACTIVE'")
    List<AnomalyDetectionResult> findActiveByEntityId(@Param("entityId") Long entityId);

    @Select("SELECT severity, COUNT(*) as count FROM anomaly_detection_result " +
            "WHERE detected_at BETWEEN #{startTime} AND #{endTime} " +
            "GROUP BY severity")
    List<Map<String, Object>> countBySeverityAndTimeRange(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    @Select("SELECT AVG(anomaly_score) FROM anomaly_detection_result " +
            "WHERE metric_code = #{metricCode} AND detected_at >= #{startTime}")
    BigDecimal getAverageAnomalyScore(
            @Param("metricCode") String metricCode,
            @Param("startTime") LocalDateTime startTime);
}
