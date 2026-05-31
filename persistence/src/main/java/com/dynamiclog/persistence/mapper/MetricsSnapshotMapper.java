package com.dynamiclog.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dynamiclog.common.entity.MetricsSnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface MetricsSnapshotMapper extends BaseMapper<MetricsSnapshot> {

    @Select("SELECT * FROM metrics_snapshot WHERE namespace = #{namespace} AND timestamp BETWEEN #{start} AND #{end} AND deleted = 0 ORDER BY timestamp ASC")
    List<MetricsSnapshot> findByNamespaceAndTimeRange(@Param("namespace") String namespace, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}
