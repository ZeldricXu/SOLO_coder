package com.meshcontrol.audit.mapper;

import com.meshcontrol.common.base.BaseMapper;
import com.meshcontrol.audit.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {

    @Select("SELECT * FROM audit_log WHERE resource_type = #{resourceType} AND resource_id = #{resourceId} ORDER BY created_at DESC")
    List<AuditLog> findByResource(@Param("resourceType") String resourceType,
                                  @Param("resourceId") String resourceId);

    @Select("SELECT * FROM audit_log WHERE operator = #{operator} ORDER BY created_at DESC")
    List<AuditLog> findByOperator(@Param("operator") String operator);

    @Select("SELECT * FROM audit_log WHERE command_id = #{commandId} ORDER BY created_at ASC")
    List<AuditLog> findByCommandId(@Param("commandId") String commandId);

    @Select("SELECT * FROM audit_log WHERE created_at BETWEEN #{startTime} AND #{endTime} ORDER BY created_at DESC")
    List<AuditLog> findByTimeRange(@Param("startTime") LocalDateTime startTime,
                                   @Param("endTime") LocalDateTime endTime);

    @Select("SELECT * FROM audit_log WHERE event_id = #{eventId}")
    AuditLog findByEventId(@Param("eventId") String eventId);
}
