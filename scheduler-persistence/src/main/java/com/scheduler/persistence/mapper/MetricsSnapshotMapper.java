package com.scheduler.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.scheduler.persistence.entity.MetricsSnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.time.Instant;
import java.util.List;

@Mapper
public interface MetricsSnapshotMapper extends BaseMapper<MetricsSnapshot> {

    @Select("SELECT * FROM metrics_snapshots WHERE snapshot_id = #{snapshotId}")
    MetricsSnapshot findBySnapshotId(@Param("snapshotId") String snapshotId);

    @Select("SELECT * FROM metrics_snapshots WHERE timestamp >= #{start} AND timestamp <= #{end} ORDER BY timestamp ASC")
    List<MetricsSnapshot> findByTimeRange(@Param("start") Instant start, @Param("end") Instant end);

    @Select("SELECT * FROM metrics_snapshots WHERE namespace = #{namespace} AND timestamp >= #{start} ORDER BY timestamp ASC")
    List<MetricsSnapshot> findByNamespaceAndTimeRange(@Param("namespace") String namespace,
                                                      @Param("start") Instant start);
}
