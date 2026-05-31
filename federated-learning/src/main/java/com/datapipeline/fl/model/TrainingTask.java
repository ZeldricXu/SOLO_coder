package com.datapipeline.fl.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingTask {

    public enum Status {
        PENDING,
        DISPATCHED,
        RUNNING,
        AGGREGATING,
        COMPLETED,
        FAILED
    }

    private String taskId;
    private String modelName;
    @Builder.Default
    private Status status = Status.PENDING;
    private int currentRound;
    private int totalRounds;
    private int minParticipants;
    private int maxParticipants;
    private Duration roundTimeout;
    private Map<String, Object> hyperparameters;
    @Builder.Default
    private Set<String> assignedClients = new HashSet<>();
    @Builder.Default
    private Map<String, LocalGradient> receivedGradients = new HashMap<>();
    private GlobalModel initialModel;
    private GlobalModel finalModel;
    @Builder.Default
    private Instant createdAt = Instant.now();
    private Instant startedAt;
    private Instant completedAt;
    private String errorMessage;

}
