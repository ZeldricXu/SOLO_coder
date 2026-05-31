package com.datastandard.modules.metrics.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datastandard.modules.metrics.entity.MetricSnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

@Mapper
public interface MetricSnapshotMapper extends BaseMapper<MetricSnapshot> {

    @Select("SELECT * FROM metric_snapshots WHERE timestamp >= #{startTime} AND timestamp < #{endTime} " +
            "AND aggregate_level = #{aggregateLevel} ORDER BY timestamp ASC")
    List<MetricSnapshot> findByTimeRangeAndLevel(
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime,
            @Param("aggregateLevel") String aggregateLevel);

    @Select("SELECT * FROM metric_snapshots WHERE timestamp >= #{startTime} AND timestamp < #{endTime} " +
            "AND aggregate_level = #{aggregateLevel} AND metrics ->> '$.metricName' = #{metricName} " +
            "ORDER BY timestamp ASC")
    List<MetricSnapshot> findByMetricNameAndTimeRange(
            @Param("metricName") String metricName,
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime,
            @Param("aggregateLevel") String aggregateLevel);

    @Select("DELETE FROM metric_snapshots WHERE timestamp < #{cutoffTime} AND aggregate_level = #{aggregateLevel}")
    int deleteOldSnapshots(
            @Param("cutoffTime") Instant cutoffTime,
            @Param("aggregateLevel") String aggregateLevel);

    @Select("SELECT COUNT(*) FROM metric_snapshots WHERE timestamp >= #{startTime} AND timestamp < #{endTime}")
    long countByTimeRange(
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime);
}
