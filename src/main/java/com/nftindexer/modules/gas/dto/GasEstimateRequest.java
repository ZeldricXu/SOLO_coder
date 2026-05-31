package com.nftindexer.modules.gas.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigInteger;

@Data
public class GasEstimateRequest {

    @NotBlank(message = "链ID不能为空")
    private String chainId;

    private String contractAddress;

    private String transactionType;

    private BigInteger gasLimit;

    private Integer historicalBlocks;

    private Integer priorityLevel;
}
