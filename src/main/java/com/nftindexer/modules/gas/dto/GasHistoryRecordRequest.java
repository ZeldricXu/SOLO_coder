package com.nftindexer.modules.gas.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigInteger;
import java.time.LocalDateTime;

@Data
public class GasHistoryRecordRequest {

    @NotBlank(message = "链ID不能为空")
    private String chainId;

    @NotNull(message = "区块号不能为空")
    private Integer blockNumber;

    @NotNull(message = "基础费用不能为空")
    private BigInteger baseFee;

    private BigInteger gasUsed;

    private BigInteger gasLimit;

    private Double gasUtilization;

    private BigInteger priorityFeeMin;

    private BigInteger priorityFeeAvg;

    private BigInteger priorityFeeMax;

    private LocalDateTime blockTime;
}
