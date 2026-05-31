package com.chaoslab.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chaoslab.entity.EventLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface EventLogMapper extends BaseMapper<EventLog> {

    @Select("SELECT COALESCE(MAX(sequence_number), 0) + 1 FROM event_log WHERE aggregate_id = #{aggregateId}")
    Long getNextSequence(@Param("aggregateId") String aggregateId);

    @Select("SELECT COALESCE(MAX(sequence_number), 0) FROM event_log")
    Long getGlobalMaxSequence();

    @Select("SELECT * FROM event_log WHERE aggregate_id = #{aggregateId} AND sequence_number > #{fromSequence} ORDER BY sequence_number ASC LIMIT #{limit}")
    List<EventLog> findByAggregateIdAndSequence(
            @Param("aggregateId") String aggregateId,
            @Param("fromSequence") Long fromSequence,
            @Param("limit") Integer limit);

    @Select("SELECT * FROM event_log WHERE timestamp <= #{timestamp} AND aggregate_id = #{aggregateId} ORDER BY sequence_number ASC")
    List<EventLog> findByAggregateIdAndTimestamp(
            @Param("aggregateId") String aggregateId,
            @Param("timestamp") LocalDateTime timestamp);
}
