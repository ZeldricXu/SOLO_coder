package com.chain.infrastructure.gasestimator.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class GasEstimateRequest {

    private String chainType;

    private String txType;

    private String contractAddress;

    private String data;

    private Integer blocksBack;
}
