package com.meshcontrol.eventstore.mapper;

import com.meshcontrol.common.base.BaseMapper;
import com.meshcontrol.eventstore.entity.EventLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface EventLogMapper extends BaseMapper<EventLog> {

    @Select("SELECT MAX(version) FROM event_log WHERE aggregate_id = #{aggregateId} AND aggregate_type = #{aggregateType}")
    Integer findMaxVersion(@Param("aggregateId") String aggregateId, @Param("aggregateType") String aggregateType);

    @Select("SELECT * FROM event_log WHERE aggregate_id = #{aggregateId} AND aggregate_type = #{aggregateType} AND version > #{version} ORDER BY version ASC")
    List<EventLog> findByAggregateIdAndVersionGreaterThan(@Param("aggregateId") String aggregateId, @Param("aggregateType") String aggregateType, @Param("version") Integer version);

    @Select("SELECT * FROM event_log WHERE aggregate_id = #{aggregateId} AND aggregate_type = #{aggregateType} AND created_at <= #{timestamp} ORDER BY version ASC")
    List<EventLog> findByAggregateIdAndTimestampBefore(@Param("aggregateId") String aggregateId, @Param("aggregateType") String aggregateType, @Param("timestamp") LocalDateTime timestamp);
}
