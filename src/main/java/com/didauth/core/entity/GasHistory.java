package com.didauth.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_gas_history")
public class GasHistory extends BaseEntity {

    private String chainType;
    private Long blockNumber;
    private String baseFee;
    private String avgGasPrice;
    private BigDecimal gasUsedRatio;
    private Long timestamp;
}
