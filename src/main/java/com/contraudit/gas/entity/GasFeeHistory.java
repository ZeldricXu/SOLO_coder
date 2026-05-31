package com.contraudit.gas.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.contraudit.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("gas_fee_history")
public class GasFeeHistory extends BaseEntity {

    private String chainType;

    private String networkId;

    private String txType;

    private BigDecimal gasPrice;

    private BigDecimal gasUsed;

    private BigDecimal gasLimit;

    private BigDecimal priorityFee;

    private BigDecimal baseFee;

    private Long blockNumber;

    private String txHash;

    private String fromAddress;

    private String toAddress;

    private BigDecimal txValue;

    private LocalDateTime recordedAt;
}
