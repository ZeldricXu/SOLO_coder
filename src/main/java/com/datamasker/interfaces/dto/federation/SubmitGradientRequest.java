package com.datamasker.interfaces.dto.federation;

import lombok.Data;

@Data
public class SubmitGradientRequest {

    private String participantId;

    private String encryptedGradient;

    private String localModelHash;

    private int dataSampleCount;
}
