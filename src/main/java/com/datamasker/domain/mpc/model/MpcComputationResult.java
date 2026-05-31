package com.datamasker.domain.mpc.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MpcComputationResult {

    private String sessionId;
    private String result;
    private int participantCount;
    private LocalDateTime completedAt;
    private boolean verified;
}
