package com.datamasker.domain.federation.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class FederationParticipant {

    private String participantId;

    private String taskId;

    private String encryptedGradient;

    private String localModelHash;

    private int dataSampleCount;

    private LocalDateTime submittedAt;
}
