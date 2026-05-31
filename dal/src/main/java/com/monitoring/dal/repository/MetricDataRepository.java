package com.monitoring.dal.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.monitoring.persistence.entity.MetricDataDO;
import com.monitoring.persistence.mapper.MetricDataMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class MetricDataRepository {

    private final MetricDataMapper metricDataMapper;

    public int save(MetricDataDO metricDataDO) {
        return metricDataMapper.insert(metricDataDO);
    }

    public List<MetricDataDO> findByMetricNameAndTimeRange(String metricName, Instant startTime, Instant endTime) {
        return metricDataMapper.selectList(
                new LambdaQueryWrapper<MetricDataDO>()
                        .eq(MetricDataDO::getMetricName, metricName)
                        .ge(MetricDataDO::getTimestamp, startTime)
                        .lt(MetricDataDO::getTimestamp, endTime)
                        .orderByAsc(MetricDataDO::getTimestamp)
        );
    }

    public List<MetricDataDO> findByMetricNameAndHour(String metricName, Long hourTimestamp) {
        return metricDataMapper.selectList(
                new LambdaQueryWrapper<MetricDataDO>()
                        .eq(MetricDataDO::getMetricName, metricName)
                        .eq(MetricDataDO::getTimestampHour, hourTimestamp)
                        .orderByAsc(MetricDataDO::getTimestamp)
        );
    }

    public void batchSave(List<MetricDataDO> metricDataList) {
        for (MetricDataDO metricDataDO : metricDataList) {
            metricDataMapper.insert(metricDataDO);
        }
    }

    public int deleteByTimeBefore(Instant time) {
        return metricDataMapper.delete(
                new LambdaQueryWrapper<MetricDataDO>()
                        .lt(MetricDataDO::getTimestamp, time)
        );
    }
}
