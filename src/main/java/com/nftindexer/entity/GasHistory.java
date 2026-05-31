package com.nftindexer.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigInteger;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("gas_history")
public class GasHistory extends BaseEntity {

    private String historyId;
    private String chainId;
    private Integer blockNumber;
    private BigInteger baseFee;
    private BigInteger gasUsed;
    private BigInteger gasLimit;
    private Double gasUtilization;
    private BigInteger priorityFeeMin;
    private BigInteger priorityFeeAvg;
    private BigInteger priorityFeeMax;
    private LocalDateTime blockTime;
}
