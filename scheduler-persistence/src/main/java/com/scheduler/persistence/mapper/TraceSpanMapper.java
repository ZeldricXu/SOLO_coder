package com.scheduler.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.scheduler.persistence.entity.TraceSpan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.time.Instant;
import java.util.List;

@Mapper
public interface TraceSpanMapper extends BaseMapper<TraceSpan> {

    @Select("SELECT * FROM trace_spans WHERE trace_id = #{traceId} ORDER BY start_time ASC")
    List<TraceSpan> findByTraceId(@Param("traceId") String traceId);

    @Select("SELECT * FROM trace_spans WHERE service_name = #{serviceName} AND start_time >= #{start} AND start_time <= #{end}")
    List<TraceSpan> findByServiceNameAndTimeRange(@Param("serviceName") String serviceName,
                                                  @Param("start") Instant start,
                                                  @Param("end") Instant end);

    @Select("SELECT DISTINCT service_name FROM trace_spans WHERE start_time >= #{start}")
    List<String> findDistinctServices(@Param("start") Instant start);
}
