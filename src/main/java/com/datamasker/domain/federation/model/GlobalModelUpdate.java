package com.datamasker.domain.federation.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class GlobalModelUpdate {

    private String taskId;

    private int roundNumber;

    private String aggregatedGradient;

    private String globalModelHash;

    private int participantCount;

    private double convergenceMetric;

    private LocalDateTime updatedAt;
}
