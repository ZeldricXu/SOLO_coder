package com.datamasker.domain.federation.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class FederationTask {

    private String taskId;

    private int roundNumber;

    private int participantCount;

    private String status;

    private List<FederationParticipant> participants;

    private String globalModelHash;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
