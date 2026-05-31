package com.orchestration.monitoring.service;

import com.orchestration.persistence.entity.MetricAggregate;
import com.orchestration.persistence.entity.MetricData;
import com.orchestration.persistence.entity.MetricDefinition;
import java.util.List;
import java.util.Map;

public interface MetricService {

    Long defineMetric(MetricDefinition definition);

    MetricDefinition getMetricDefinition(Long id);

    List<MetricDefinition> listMetricDefinitions();

    void collectMetric(String metricCode, Double value, Map<String, String> labels);

    void batchCollectMetrics(List<MetricData> metrics);

    List<MetricData> queryMetricData(String metricCode, Long startTime, Long endTime, Map<String, String> labels);

    List<MetricAggregate> queryMetricAggregate(
            String metricCode,
            String aggregateType,
            String aggregatePeriod,
            Long startTime,
            Long endTime);

    Map<String, Object> getDashboardData();

    void aggregateMetrics();

    List<Map<String, Object>> getTopMetrics(int limit);
}
