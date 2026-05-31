package com.contraudit.gas.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.contraudit.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("gas_price_oracle")
public class GasPriceOracle extends BaseEntity {

    private String chainType;

    private String networkId;

    private String oracleName;

    private String oracleUrl;

    private BigDecimal currentGasPrice;

    private BigDecimal priorityFee;

    private BigDecimal baseFee;

    private BigDecimal minGasPrice;

    private BigDecimal maxGasPrice;

    private Integer status;

    private LocalDateTime lastUpdated;
}
