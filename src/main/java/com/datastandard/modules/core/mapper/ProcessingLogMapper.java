package com.datastandard.modules.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datastandard.modules.core.entity.ProcessingLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Mapper
public interface ProcessingLogMapper extends BaseMapper<ProcessingLog> {

    @Select("SELECT * FROM processing_logs WHERE request_id = #{requestId} AND deleted = 0")
    Optional<ProcessingLog> findByRequestId(@Param("requestId") String requestId);

    @Select("SELECT * FROM processing_logs WHERE data_source = #{dataSource} " +
            "AND created_at >= #{startTime} AND created_at < #{endTime} AND deleted = 0 " +
            "ORDER BY created_at DESC")
    List<ProcessingLog> findByDataSourceAndTimeRange(
            @Param("dataSource") String dataSource,
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime);

    @Select("SELECT COUNT(*) FROM processing_logs WHERE status = #{status} " +
            "AND created_at >= #{startTime} AND created_at < #{endTime}")
    long countByStatusAndTimeRange(
            @Param("status") String status,
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime);
}
