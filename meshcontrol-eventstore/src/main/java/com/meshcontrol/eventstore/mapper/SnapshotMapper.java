package com.meshcontrol.eventstore.mapper;

import com.meshcontrol.common.base.BaseMapper;
import com.meshcontrol.eventstore.entity.Snapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SnapshotMapper extends BaseMapper<Snapshot> {

    @Select("SELECT * FROM snapshot WHERE aggregate_id = #{aggregateId} AND aggregate_type = #{aggregateType} ORDER BY version DESC LIMIT 1")
    Snapshot findLatestByAggregateId(@Param("aggregateId") String aggregateId,
                                     @Param("aggregateType") String aggregateType);

    @Select("SELECT * FROM snapshot WHERE aggregate_id = #{aggregateId} AND aggregate_type = #{aggregateType} AND timestamp <= #{timestamp} ORDER BY version DESC LIMIT 1")
    Snapshot findByAggregateIdAndTimestampBefore(@Param("aggregateId") String aggregateId,
                                                 @Param("aggregateType") String aggregateType,
                                                 @Param("timestamp") LocalDateTime timestamp);

    @Select("SELECT * FROM snapshot WHERE aggregate_id = #{aggregateId} AND aggregate_type = #{aggregateType} ORDER BY version DESC")
    List<Snapshot> findAllByAggregateId(@Param("aggregateId") String aggregateId,
                                        @Param("aggregateType") String aggregateType);
}
