package com.supplychain.common.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupplierEvaluation implements Serializable {
    private String evaluationId;
    private String supplierId;
    private Double qualityScore;
    private Double deliveryScore;
    private Double priceScore;
    private Double serviceScore;
    private Double totalScore;
    private String evaluationResult;
    private String evaluator;
    private LocalDateTime evaluationTime;
}
