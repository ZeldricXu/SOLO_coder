package com.monitoring.dal.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.monitoring.persistence.entity.TraceSpanDO;
import com.monitoring.persistence.mapper.TraceSpanMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class TraceSpanRepository {

    private final TraceSpanMapper traceSpanMapper;

    public int save(TraceSpanDO traceSpanDO) {
        return traceSpanMapper.insert(traceSpanDO);
    }

    public List<TraceSpanDO> findByTraceId(String traceId) {
        return traceSpanMapper.selectList(
                new LambdaQueryWrapper<TraceSpanDO>()
                        .eq(TraceSpanDO::getTraceId, traceId)
                        .orderByAsc(TraceSpanDO::getStartTime)
        );
    }

    public List<TraceSpanDO> findByServiceNameAndTimeRange(String serviceName, Instant startTime, Instant endTime) {
        return traceSpanMapper.selectList(
                new LambdaQueryWrapper<TraceSpanDO>()
                        .eq(TraceSpanDO::getServiceName, serviceName)
                        .ge(TraceSpanDO::getStartTime, startTime)
                        .lt(TraceSpanDO::getStartTime, endTime)
                        .orderByDesc(TraceSpanDO::getStartTime)
        );
    }

    public List<TraceSpanDO> findSampledSpans(Instant startTime, Instant endTime) {
        return traceSpanMapper.selectList(
                new LambdaQueryWrapper<TraceSpanDO>()
                        .eq(TraceSpanDO::getSampled, true)
                        .ge(TraceSpanDO::getStartTime, startTime)
                        .lt(TraceSpanDO::getStartTime, endTime)
                        .orderByDesc(TraceSpanDO::getStartTime)
        );
    }

    public void batchSave(List<TraceSpanDO> spanList) {
        for (TraceSpanDO traceSpanDO : spanList) {
            traceSpanMapper.insert(traceSpanDO);
        }
    }

    public int deleteByTimeBefore(Instant time) {
        return traceSpanMapper.delete(
                new LambdaQueryWrapper<TraceSpanDO>()
                        .lt(TraceSpanDO::getStartTime, time)
        );
    }
}
