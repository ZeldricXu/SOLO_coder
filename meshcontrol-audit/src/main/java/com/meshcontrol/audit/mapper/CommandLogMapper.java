package com.meshcontrol.audit.mapper;

import com.meshcontrol.common.base.BaseMapper;
import com.meshcontrol.audit.entity.CommandLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CommandLogMapper extends BaseMapper<CommandLog> {

    @Select("SELECT * FROM command_log WHERE aggregate_id = #{aggregateId} AND aggregate_type = #{aggregateType} ORDER BY executed_at ASC")
    List<CommandLog> findByAggregate(@Param("aggregateId") String aggregateId,
                                     @Param("aggregateType") String aggregateType);

    @Select("SELECT * FROM command_log WHERE command_type = #{commandType} ORDER BY executed_at DESC")
    List<CommandLog> findByCommandType(@Param("commandType") String commandType);

    @Select("SELECT * FROM command_log WHERE executed_by = #{executedBy} ORDER BY executed_at DESC")
    List<CommandLog> findByExecutor(@Param("executedBy") String executedBy);

    @Select("SELECT * FROM command_log WHERE executed_at BETWEEN #{startTime} AND #{endTime} ORDER BY executed_at DESC")
    List<CommandLog> findByTimeRange(@Param("startTime") LocalDateTime startTime,
                                     @Param("endTime") LocalDateTime endTime);
}
