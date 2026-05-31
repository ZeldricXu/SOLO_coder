package com.taskplatform.test.builder;

import com.taskplatform.common.util.IdGenerator;
import com.taskplatform.common.util.JsonUtil;
import com.taskplatform.persistence.entity.Experiment;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public class ExperimentBuilder {

    private String experimentId;
    private String name;
    private String description;
    private String type = "AB_TEST";
    private String status = "DRAFT";
    private String controlPromptId;
    private String treatmentPromptIds;
    private String trafficSplit;
    private String metrics;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String createdBy = "test-user";
    private String result;

    public static ExperimentBuilder anExperiment() {
        return new ExperimentBuilder();
    }

    public ExperimentBuilder withExperimentId(String experimentId) {
        this.experimentId = experimentId;
        return this;
    }

    public ExperimentBuilder withName(String name) {
        this.name = name;
        return this;
    }

    public ExperimentBuilder withDescription(String description) {
        this.description = description;
        return this;
    }

    public ExperimentBuilder withType(String type) {
        this.type = type;
        return this;
    }

    public ExperimentBuilder withStatus(String status) {
        this.status = status;
        return this;
    }

    public ExperimentBuilder withControlPromptId(String controlPromptId) {
        this.controlPromptId = controlPromptId;
        return this;
    }

    public ExperimentBuilder withTreatmentPromptIds(List<String> treatmentPromptIds) {
        this.treatmentPromptIds = JsonUtil.toJson(treatmentPromptIds);
        return this;
    }

    public ExperimentBuilder withTrafficSplit(Map<String, Double> trafficSplit) {
        this.trafficSplit = JsonUtil.toJson(trafficSplit);
        return this;
    }

    public ExperimentBuilder withMetrics(Map<String, Object> metrics) {
        this.metrics = JsonUtil.toJson(metrics);
        return this;
    }

    public ExperimentBuilder withStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
        return this;
    }

    public ExperimentBuilder withEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
        return this;
    }

    public ExperimentBuilder withCreatedBy(String createdBy) {
        this.createdBy = createdBy;
        return this;
    }

    public ExperimentBuilder withResult(String result) {
        this.result = result;
        return this;
    }

    public Experiment build() {
        Experiment experiment = new Experiment();
        experiment.setExperimentId(experimentId != null ? experimentId : IdGenerator.generateExperimentId());
        experiment.setName(name != null ? name : "Test Experiment - " + System.currentTimeMillis());
        experiment.setDescription(description);
        experiment.setType(type);
        experiment.setStatus(status);
        experiment.setControlPromptId(controlPromptId != null ? controlPromptId : IdGenerator.generatePromptId());
        experiment.setTreatmentPromptIds(treatmentPromptIds != null ? treatmentPromptIds :
                JsonUtil.toJson(List.of(IdGenerator.generatePromptId())));
        experiment.setTrafficSplit(trafficSplit != null ? trafficSplit :
                JsonUtil.toJson(Map.of("control", 50.0, "treatment_0", 50.0)));
        experiment.setMetrics(metrics);
        experiment.setStartTime(startTime);
        experiment.setEndTime(endTime);
        experiment.setCreatedBy(createdBy);
        experiment.setResult(result);
        experiment.setCreatedAt(LocalDateTime.now());
        experiment.setUpdatedAt(LocalDateTime.now());
        return experiment;
    }

    public Experiment buildDraftExperiment() {
        this.status = "DRAFT";
        return build();
    }

    public Experiment buildRunningExperiment() {
        this.status = "RUNNING";
        this.startTime = LocalDateTime.now().minusHours(1);
        return build();
    }

    public Experiment buildStoppedExperiment() {
        this.status = "STOPPED";
        this.startTime = LocalDateTime.now().minusDays(1);
        this.endTime = LocalDateTime.now();
        return build();
    }
}
