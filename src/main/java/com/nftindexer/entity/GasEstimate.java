package com.nftindexer.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("gas_estimate")
public class GasEstimate extends BaseEntity {

    private String estimateId;
    private String chainId;
    private BigInteger baseFee;
    private BigInteger priorityFeeLow;
    private BigInteger priorityFeeMedium;
    private BigInteger priorityFeeHigh;
    private BigInteger maxFeeLow;
    private BigInteger maxFeeMedium;
    private BigInteger maxFeeHigh;
    private BigInteger gasLimit;
    private BigInteger estimatedCostLow;
    private BigInteger estimatedCostMedium;
    private BigInteger estimatedCostHigh;
    private Integer blockNumber;
    private LocalDateTime timestamp;
    private Map<String, Object> historicalData;
    private Map<String, Object> networkStatus;
}
