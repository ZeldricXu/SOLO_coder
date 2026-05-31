package com.datamasker.interfaces.dto.federation;

import lombok.Data;

@Data
public class GlobalModelResponse {

    private String taskId;

    private int roundNumber;

    private String globalModelHash;

    private int participantCount;

    private double convergenceMetric;

    private boolean converged;
}
