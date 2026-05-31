package com.datapipeline.fl.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LocalGradient {

    private String gradientId;
    private String clientId;
    private String taskId;
    private int round;
    private Map<String, double[]> gradients;
    private int sampleCount;
    private double loss;
    private byte[] encryptedPayload;
    @Builder.Default
    private Instant receivedAt = Instant.now();
    private String signature;

}
