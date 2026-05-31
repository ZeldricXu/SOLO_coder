package com.chainetl.modules.gas.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GasPriceOracleResponse {

    private String chainId;
    private Long baseFee;
    private Long slowGasPrice;
    private Long standardGasPrice;
    private Long fastGasPrice;
    private Long slowPriorityFee;
    private Long standardPriorityFee;
    private Long fastPriorityFee;
    private Instant timestamp;
}
