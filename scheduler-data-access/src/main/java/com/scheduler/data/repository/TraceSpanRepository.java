package com.scheduler.data.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.scheduler.persistence.entity.TraceSpan;
import com.scheduler.persistence.mapper.TraceSpanMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class TraceSpanRepository {

    private final TraceSpanMapper traceSpanMapper;

    public TraceSpan create(TraceSpan span) {
        traceSpanMapper.insert(span);
        return span;
    }

    public TraceSpan findById(String id) {
        return traceSpanMapper.selectById(id);
    }

    public List<TraceSpan> findByTraceId(String traceId) {
        QueryWrapper<TraceSpan> wrapper = new QueryWrapper<>();
        wrapper.eq("trace_id", traceId).orderByAsc("start_time");
        return traceSpanMapper.selectList(wrapper);
    }

    public List<TraceSpan> findByServiceName(String serviceName, LocalDateTime startTime, LocalDateTime endTime) {
        QueryWrapper<TraceSpan> wrapper = new QueryWrapper<>();
        wrapper.eq("service_name", serviceName)
                .between("start_time", startTime, endTime)
                .orderByDesc("start_time");
        return traceSpanMapper.selectList(wrapper);
    }

    public List<TraceSpan> findByTimeRange(LocalDateTime startTime, LocalDateTime endTime, int limit) {
        QueryWrapper<TraceSpan> wrapper = new QueryWrapper<>();
        wrapper.between("start_time", startTime, endTime)
                .orderByDesc("start_time")
                .last("LIMIT " + limit);
        return traceSpanMapper.selectList(wrapper);
    }

    public List<TraceSpan> findByOperationName(String operationName, LocalDateTime startTime, LocalDateTime endTime) {
        QueryWrapper<TraceSpan> wrapper = new QueryWrapper<>();
        wrapper.eq("operation_name", operationName)
                .between("start_time", startTime, endTime)
                .orderByDesc("start_time");
        return traceSpanMapper.selectList(wrapper);
    }
}
