package com.apishield.fl.domain;

import com.apishield.domain.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class FlTrainingTask extends BaseEntity {
    private String taskId;
    private String modelName;
    private String modelVersion;
    private TaskStatus status;
    private List<String> participantIds;
    private int currentRound;
    private int totalRounds;
    private Map<String, Object> hyperparameters;
    private Map<String, Object> globalModel;
    private Map<String, Object> aggregatedGradients;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private double accuracy;
    private double loss;

    public FlTrainingTask() {
        this.participantIds = new ArrayList<>();
        this.hyperparameters = new HashMap<>();
        this.globalModel = new HashMap<>();
        this.aggregatedGradients = new HashMap<>();
    }

    public enum TaskStatus {
        CREATED, INITIALIZING, TRAINING, AGGREGATING, EVALUATING, COMPLETED, FAILED, CANCELLED
    }
}
