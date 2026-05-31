package com.solocoder.platform.gas.estimator.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GasHistory {

    private Long id;
    private String chainId;
    private Long blockNumber;
    private BigDecimal gasPrice;
    private BigDecimal baseFee;
    private BigDecimal priorityFee;
    private Long gasUsed;
    private Long gasLimit;
    private Integer transactionCount;
    private LocalDateTime blockTime;
    private LocalDateTime createdAt;
}
