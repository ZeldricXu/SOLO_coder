package com.datastandard.modules.slo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datastandard.modules.slo.entity.SliMetric;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Mapper
public interface SliMetricMapper extends BaseMapper<SliMetric> {

    @Select("SELECT * FROM sli_metrics WHERE metric_id = #{metricId} AND deleted = 0")
    Optional<SliMetric> findById(@Param("metricId") String metricId);

    @Select("SELECT * FROM sli_metrics WHERE slo_id = #{sloId} AND deleted = 0 ORDER BY created_at DESC LIMIT #{limit}")
    List<SliMetric> findBySloId(@Param("sloId") String sloId, @Param("limit") int limit);

    @Select("SELECT * FROM sli_metrics WHERE slo_id = #{sloId} " +
            "AND window_start >= #{startTime} AND window_end < #{endTime} AND deleted = 0 " +
            "ORDER BY window_start ASC")
    List<SliMetric> findBySloIdAndTimeRange(
            @Param("sloId") String sloId,
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime);

    @Select("SELECT * FROM sli_metrics WHERE slo_id = #{sloId} AND deleted = 0 " +
            "ORDER BY created_at DESC LIMIT 1")
    Optional<SliMetric> findLatestBySloId(@Param("sloId") String sloId);

    @Select("SELECT * FROM sli_metrics WHERE sli_type = #{sliType} " +
            "AND window_start >= #{startTime} AND window_end < #{endTime} AND deleted = 0 " +
            "ORDER BY window_start ASC")
    List<SliMetric> findByTypeAndTimeRange(
            @Param("sliType") String sliType,
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime);
}
