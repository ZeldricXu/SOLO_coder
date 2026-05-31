package com.orchestration.monitoring.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.orchestration.common.exception.BusinessException;
import com.orchestration.common.util.JsonUtil;
import com.orchestration.monitoring.service.MetricService;
import com.orchestration.persistence.entity.MetricAggregate;
import com.orchestration.persistence.entity.MetricData;
import com.orchestration.persistence.entity.MetricDefinition;
import com.orchestration.persistence.mapper.MetricAggregateMapper;
import com.orchestration.persistence.mapper.MetricDataMapper;
import com.orchestration.persistence.mapper.MetricDefinitionMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MetricServiceImpl implements MetricService {

    private final MetricDefinitionMapper definitionMapper;
    private final MetricDataMapper dataMapper;
    private final MetricAggregateMapper aggregateMapper;
    private final MeterRegistry meterRegistry;

    private final Map<String, Counter> counterCache = new ConcurrentHashMap<>();
    private final Map<String, AtomicDouble> gaugeCache = new ConcurrentHashMap<>();

    @Override
    public Long defineMetric(MetricDefinition definition) {
        MetricDefinition existing = definitionMapper.selectOne(
                new LambdaQueryWrapper<MetricDefinition>()
                        .eq(MetricDefinition::getMetricCode, definition.getMetricCode())
        );
        if (existing != null) {
            throw new BusinessException("指标编码已存在");
        }
        definitionMapper.insert(definition);
        return definition.getId();
    }

    @Override
    public MetricDefinition getMetricDefinition(Long id) {
        return definitionMapper.selectById(id);
    }

    @Override
    public List<MetricDefinition> listMetricDefinitions() {
        return definitionMapper.selectList(
                new LambdaQueryWrapper<MetricDefinition>().eq(MetricDefinition::getEnabled, 1)
        );
    }

    @Override
    public void collectMetric(String metricCode, Double value, Map<String, String> labels) {
        MetricDefinition definition = definitionMapper.selectOne(
                new LambdaQueryWrapper<MetricDefinition>()
                        .eq(MetricDefinition::getMetricCode, metricCode)
        );
        if (definition == null) {
            log.warn("指标不存在: {}", metricCode);
            return;
        }

        MetricData data = new MetricData();
        data.setMetricId(definition.getId());
        data.setMetricValue(BigDecimal.valueOf(value));
        data.setLabelsJson(labels != null ? JsonUtil.toJson(labels) : null);
        data.setTimestampMs(System.currentTimeMillis());
        dataMapper.insert(data);

        updateMicrometerMetric(definition, value, labels);
    }

    private void updateMicrometerMetric(MetricDefinition definition, Double value, Map<String, String> labels) {
        Tags tags = Tags.empty();
        if (labels != null) {
            for (Map.Entry<String, String> entry : labels.entrySet()) {
                tags = tags.and(entry.getKey(), entry.getValue());
            }
        }

        String metricType = definition.getMetricType();
        if ("counter".equalsIgnoreCase(metricType)) {
            String key = definition.getMetricCode() + "_" + tags;
            Counter counter = counterCache.computeIfAbsent(key, k ->
                    Counter.builder(definition.getMetricCode())
                            .description(definition.getDescription())
                            .tags(tags)
                            .register(meterRegistry)
            );
            counter.increment(value);
        } else if ("gauge".equalsIgnoreCase(metricType)) {
            String key = definition.getMetricCode() + "_" + tags;
            AtomicDouble atomicValue = gaugeCache.computeIfAbsent(key, k -> {
                AtomicDouble av = new AtomicDouble(0);
                Gauge.builder(definition.getMetricCode(), av, AtomicDouble::get)
                        .description(definition.getDescription())
                        .tags(tags)
                        .register(meterRegistry);
                return av;
            });
            atomicValue.set(value);
        }
    }

    @Override
    public void batchCollectMetrics(List<MetricData> metrics) {
        for (MetricData data : metrics) {
            dataMapper.insert(data);
        }
    }

    @Override
    public List<MetricData> queryMetricData(String metricCode, Long startTime, Long endTime, Map<String, String> labels) {
        MetricDefinition definition = definitionMapper.selectOne(
                new LambdaQueryWrapper<MetricDefinition>()
                        .eq(MetricDefinition::getMetricCode, metricCode)
        );
        if (definition == null) {
            throw new BusinessException("指标不存在");
        }

        LambdaQueryWrapper<MetricData> wrapper = new LambdaQueryWrapper<MetricData>()
                .eq(MetricData::getMetricId, definition.getId())
                .ge(MetricData::getTimestampMs, startTime)
                .le(MetricData::getTimestampMs, endTime)
                .orderByDesc(MetricData::getTimestampMs);

        return dataMapper.selectList(wrapper);
    }

    @Override
    public List<MetricAggregate> queryMetricAggregate(
            String metricCode,
            String aggregateType,
            String aggregatePeriod,
            Long startTime,
            Long endTime) {

        MetricDefinition definition = definitionMapper.selectOne(
                new LambdaQueryWrapper<MetricDefinition>()
                        .eq(MetricDefinition::getMetricCode, metricCode)
        );
        if (definition == null) {
            throw new BusinessException("指标不存在");
        }

        LambdaQueryWrapper<MetricAggregate> wrapper = new LambdaQueryWrapper<MetricAggregate>()
                .eq(MetricAggregate::getMetricId, definition.getId())
                .eq(MetricAggregate::getAggregateType, aggregateType)
                .eq(MetricAggregate::getAggregatePeriod, aggregatePeriod)
                .ge(MetricAggregate::getPeriodStart, startTime)
                .le(MetricAggregate::getPeriodEnd, endTime)
                .orderByAsc(MetricAggregate::getPeriodStart);

        return aggregateMapper.selectList(wrapper);
    }

    @Override
    public Map<String, Object> getDashboardData() {
        Map<String, Object> dashboard = new HashMap<>();

        long now = System.currentTimeMillis();
        long oneHourAgo = now - 3600000;
        long oneDayAgo = now - 86400000;

        List<MetricDefinition> definitions = listMetricDefinitions();
        dashboard.put("totalMetrics", definitions.size());

        long dataCountLastHour = dataMapper.selectCount(
                new LambdaQueryWrapper<MetricData>()
                        .ge(MetricData::getTimestampMs, oneHourAgo)
        );
        dashboard.put("dataCountLastHour", dataCountLastHour);

        long dataCountLastDay = dataMapper.selectCount(
                new LambdaQueryWrapper<MetricData>()
                        .ge(MetricData::getTimestampMs, oneDayAgo)
        );
        dashboard.put("dataCountLastDay", dataCountLastDay);

        List<MetricData> recentData = dataMapper.selectList(
                new LambdaQueryWrapper<MetricData>()
                        .orderByDesc(MetricData::getCreatedAt)
                        .last("LIMIT 10")
        );
        dashboard.put("recentData", recentData);

        return dashboard;
    }

    @Override
    @Scheduled(fixedRate = 300000)
    public void aggregateMetrics() {
        log.info("开始执行指标聚合");
        long now = System.currentTimeMillis();
        long fiveMinutesAgo = now - 300000;

        List<MetricDefinition> definitions = listMetricDefinitions();
        for (MetricDefinition def : definitions) {
            try {
                aggregateMetric(def.getId(), fiveMinutesAgo, now);
            } catch (Exception e) {
                log.error("聚合指标失败: {}", def.getMetricCode(), e);
            }
        }
        log.info("指标聚合完成");
    }

    private void aggregateMetric(Long metricId, Long startTime, Long endTime) {
        List<MetricData> dataList = dataMapper.selectList(
                new LambdaQueryWrapper<MetricData>()
                        .eq(MetricData::getMetricId, metricId)
                        .ge(MetricData::getTimestampMs, startTime)
                        .le(MetricData::getTimestampMs, endTime)
        );

        if (dataList.isEmpty()) {
            return;
        }

        List<BigDecimal> values = dataList.stream()
                .map(MetricData::getMetricValue)
                .collect(Collectors.toList());

        double sum = values.stream().mapToDouble(BigDecimal::doubleValue).sum();
        double avg = sum / values.size();
        double max = values.stream().mapToDouble(BigDecimal::doubleValue).max().orElse(0);
        double min = values.stream().mapToDouble(BigDecimal::doubleValue).min().orElse(0);

        saveAggregate(metricId, "sum", startTime, endTime, sum, values.size());
        saveAggregate(metricId, "avg", startTime, endTime, avg, values.size());
        saveAggregate(metricId, "max", startTime, endTime, max, values.size());
        saveAggregate(metricId, "min", startTime, endTime, min, values.size());
        saveAggregate(metricId, "count", startTime, endTime, values.size(), values.size());
    }

    private void saveAggregate(Long metricId, String type, Long start, Long end, double value, long count) {
        MetricAggregate aggregate = new MetricAggregate();
        aggregate.setMetricId(metricId);
        aggregate.setAggregateType(type);
        aggregate.setAggregatePeriod("5m");
        aggregate.setPeriodStart(start);
        aggregate.setPeriodEnd(end);
        aggregate.setAggregateValue(BigDecimal.valueOf(value));
        aggregate.setSampleCount(count);
        aggregateMapper.insert(aggregate);
    }

    @Override
    public List<Map<String, Object>> getTopMetrics(int limit) {
        List<MetricDefinition> definitions = listMetricDefinitions();
        List<Map<String, Object>> result = new ArrayList<>();

        for (MetricDefinition def : definitions) {
            long count = dataMapper.selectCount(
                    new LambdaQueryWrapper<MetricData>().eq(MetricData::getMetricId, def.getId())
            );

            Map<String, Object> item = new HashMap<>();
            item.put("metricCode", def.getMetricCode());
            item.put("metricName", def.getMetricName());
            item.put("metricType", def.getMetricType());
            item.put("dataCount", count);
            result.add(item);
        }

        result.sort((a, b) -> Long.compare((Long) b.get("dataCount"), (Long) a.get("dataCount")));
        return result.stream().limit(limit).collect(Collectors.toList());
    }

    private static class AtomicDouble extends Number {
        private volatile double value;

        public AtomicDouble(double value) {
            this.value = value;
        }

        public void set(double value) {
            this.value = value;
        }

        public double get() {
            return value;
        }

        @Override
        public int intValue() {
            return (int) value;
        }

        @Override
        public long longValue() {
            return (long) value;
        }

        @Override
        public float floatValue() {
            return (float) value;
        }

        @Override
        public double doubleValue() {
            return value;
        }
    }
}
