package com.apishield.fl.domain;

import com.apishield.domain.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class FlClientUpdate extends BaseEntity {
    private String updateId;
    private String taskId;
    private String clientId;
    private int roundNumber;
    private Map<String, Object> encryptedGradients;
    private Map<String, Object> encryptedWeights;
    private int sampleCount;
    private double localLoss;
    private LocalDateTime submittedAt;
    private String status;
}
