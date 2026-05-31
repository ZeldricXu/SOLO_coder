package com.datastandard.modules.profiling.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datastandard.modules.profiling.entity.ProfilingSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Mapper
public interface ProfilingSessionMapper extends BaseMapper<ProfilingSession> {

    @Select("SELECT * FROM profiling_sessions WHERE session_id = #{sessionId} AND deleted = 0")
    Optional<ProfilingSession> findBySessionId(@Param("sessionId") String sessionId);

    @Select("SELECT * FROM profiling_sessions WHERE status = #{status} AND deleted = 0 " +
            "ORDER BY created_at DESC")
    List<ProfilingSession> findByStatus(@Param("status") String status);

    @Select("SELECT * FROM profiling_sessions WHERE created_by = #{createdBy} AND deleted = 0 " +
            "ORDER BY created_at DESC LIMIT #{limit}")
    List<ProfilingSession> findByCreatedBy(@Param("createdBy") String createdBy, @Param("limit") int limit);

    @Select("SELECT * FROM profiling_sessions WHERE status IN ('RUNNING', 'STARTING') AND deleted = 0")
    List<ProfilingSession> findActiveSessions();

    @Select("SELECT * FROM profiling_sessions WHERE start_time >= #{startTime} " +
            "AND start_time < #{endTime} AND deleted = 0 " +
            "ORDER BY start_time DESC")
    List<ProfilingSession> findByTimeRange(
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime);

    @Update("UPDATE profiling_sessions SET status = #{status}, end_time = #{endTime}, " +
            "actual_duration = #{actualDuration}, updated_at = #{updatedAt} " +
            "WHERE session_id = #{sessionId}")
    int updateSessionStatus(
            @Param("sessionId") String sessionId,
            @Param("status") String status,
            @Param("endTime") Instant endTime,
            @Param("actualDuration") java.time.Duration actualDuration,
            @Param("updatedAt") Instant updatedAt);

    @Update("UPDATE profiling_sessions SET status = 'FAILED', error_message = #{errorMessage}, " +
            "end_time = #{endTime}, updated_at = #{updatedAt} " +
            "WHERE session_id = #{sessionId}")
    int markAsFailed(
            @Param("sessionId") String sessionId,
            @Param("errorMessage") String errorMessage,
            @Param("endTime") Instant endTime,
            @Param("updatedAt") Instant updatedAt);

    @Update("UPDATE profiling_sessions SET flame_graph_path = #{flameGraphPath}, " +
            "cpu_report_path = #{cpuReportPath}, memory_report_path = #{memoryReportPath}, " +
            "updated_at = #{updatedAt} " +
            "WHERE session_id = #{sessionId}")
    int updateReportPaths(
            @Param("sessionId") String sessionId,
            @Param("flameGraphPath") String flameGraphPath,
            @Param("cpuReportPath") String cpuReportPath,
            @Param("memoryReportPath") String memoryReportPath,
            @Param("updatedAt") Instant updatedAt);
}
