package com.taskplatform.prompt;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.taskplatform.common.exception.BusinessException;
import com.taskplatform.common.util.IdGenerator;
import com.taskplatform.common.util.JsonUtil;
import com.taskplatform.persistence.entity.Experiment;
import com.taskplatform.persistence.mapper.ExperimentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExperimentService {

    private final ExperimentMapper experimentMapper;
    private final PromptService promptService;

    public Experiment createExperiment(Experiment experiment, String createdBy) {
        experiment.setExperimentId(IdGenerator.generateExperimentId());
        experiment.setCreatedBy(createdBy);
        experiment.setStatus("DRAFT");
        experimentMapper.insert(experiment);
        log.info("Created experiment: {}", experiment.getExperimentId());
        return experiment;
    }

    public Experiment getExperiment(String experimentId) {
        Experiment experiment = experimentMapper.selectOne(
                new LambdaQueryWrapper<Experiment>()
                        .eq(Experiment::getExperimentId, experimentId)
        );
        if (experiment == null) {
            throw new BusinessException(404, "EXPERIMENT_NOT_FOUND",
                    "Experiment not found: " + experimentId);
        }
        return experiment;
    }

    public Experiment startExperiment(String experimentId) {
        Experiment experiment = getExperiment(experimentId);

        if (!"DRAFT".equals(experiment.getStatus())) {
            throw new BusinessException(400, "INVALID_STATUS",
                    "Cannot start experiment in status: " + experiment.getStatus());
        }

        experiment.setStatus("RUNNING");
        experiment.setStartTime(LocalDateTime.now());
        experimentMapper.updateById(experiment);

        log.info("Started experiment: {}", experimentId);
        return experiment;
    }

    public Experiment stopExperiment(String experimentId) {
        Experiment experiment = getExperiment(experimentId);
        experiment.setStatus("STOPPED");
        experiment.setEndTime(LocalDateTime.now());
        experimentMapper.updateById(experiment);
        log.info("Stopped experiment: {}", experimentId);
        return experiment;
    }

    public String assignVariant(String experimentId, String userId) {
        Experiment experiment = getExperiment(experimentId);

        if (!"RUNNING".equals(experiment.getStatus())) {
            return experiment.getControlPromptId();
        }

        Map<String, Double> trafficSplit = experiment.getTrafficSplit() != null ?
                JsonUtil.fromJson(experiment.getTrafficSplit(), Map.class) : new HashMap<>();

        if (trafficSplit.isEmpty()) {
            return experiment.getControlPromptId();
        }

        int hash = Math.abs((experimentId + userId).hashCode());
        double bucket = (hash % 100) / 100.0;
        double cumulative = 0.0;

        List<String> treatmentIds = experiment.getTreatmentPromptIds() != null ?
                JsonUtil.fromJson(experiment.getTreatmentPromptIds(), List.class) : new ArrayList<>();

        if (!treatmentIds.isEmpty()) {
            for (int i = 0; i < treatmentIds.size(); i++) {
                String treatmentId = treatmentIds.get(i);
                double weight = trafficSplit.getOrDefault("treatment_" + i, 0.0);
                cumulative += weight;
                if (bucket < cumulative) {
                    return treatmentId;
                }
            }
        }

        return experiment.getControlPromptId();
    }

    public Experiment recordMetric(String experimentId, String variantId, String metricName, double value) {
        Experiment experiment = getExperiment(experimentId);

        Map<String, Object> metrics = experiment.getMetrics() != null ?
                JsonUtil.fromJson(experiment.getMetrics(), Map.class) : new HashMap<>();

        String variantKey = variantId + "." + metricName;
        List<Double> values = metrics.containsKey(variantKey) ?
                (List<Double>) metrics.get(variantKey) : new ArrayList<>();
        values.add(value);
        metrics.put(variantKey, values);

        experiment.setMetrics(JsonUtil.toJson(metrics));
        experimentMapper.updateById(experiment);

        return experiment;
    }

    public Map<String, Object> evaluateExperiment(String experimentId) {
        Experiment experiment = getExperiment(experimentId);

        if (experiment.getMetrics() == null) {
            throw new BusinessException(400, "NO_METRICS",
                    "No metrics recorded for experiment: " + experimentId);
        }

        Map<String, Object> allMetrics = JsonUtil.fromJson(experiment.getMetrics(), Map.class);
        Map<String, Object> result = new HashMap<>();
        Map<String, Map<String, Object>> variantStats = new HashMap<>();

        for (Map.Entry<String, Object> entry : allMetrics.entrySet()) {
            String key = entry.getKey();
            List<Double> values = (List<Double>) entry.getValue();

            if (!values.isEmpty()) {
                double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
                double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
                double min = values.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
                long count = values.size();

                String[] parts = key.split("\\.", 2);
                String variantId = parts[0];
                String metricName = parts.length > 1 ? parts[1] : "value";

                variantStats.computeIfAbsent(variantId, k -> new HashMap<>())
                        .put(metricName, Map.of(
                                "mean", mean,
                                "max", max,
                                "min", min,
                                "count", count
                        ));
            }
        }

        result.put("experimentId", experimentId);
        result.put("status", experiment.getStatus());
        result.put("variantStats", variantStats);

        if (experiment.getControlPromptId() != null && variantStats.size() > 1) {
            String controlId = experiment.getControlPromptId();
            Map<String, Object> controlStats = variantStats.get(controlId);
            if (controlStats != null) {
                Map<String, Object> improvements = new HashMap<>();
                for (Map.Entry<String, Map<String, Object>> variantEntry : variantStats.entrySet()) {
                    if (!variantEntry.getKey().equals(controlId)) {
                        improvements.put(variantEntry.getKey(),
                                calculateImprovement(controlStats, variantEntry.getValue()));
                    }
                }
                result.put("improvements", improvements);
            }
        }

        experiment.setResult(JsonUtil.toJson(result));
        experimentMapper.updateById(experiment);

        return result;
    }

    private Map<String, Double> calculateImprovement(Map<String, Object> control, Map<String, Object> treatment) {
        Map<String, Double> improvement = new HashMap<>();
        for (String metric : control.keySet()) {
            if (treatment.containsKey(metric)) {
                Map<String, Object> controlMetric = (Map<String, Object>) control.get(metric);
                Map<String, Object> treatmentMetric = (Map<String, Object>) treatment.get(metric);
                double controlMean = ((Number) controlMetric.get("mean")).doubleValue();
                double treatmentMean = ((Number) treatmentMetric.get("mean")).doubleValue();
                if (controlMean > 0) {
                    improvement.put(metric, (treatmentMean - controlMean) / controlMean * 100);
                }
            }
        }
        return improvement;
    }
}
