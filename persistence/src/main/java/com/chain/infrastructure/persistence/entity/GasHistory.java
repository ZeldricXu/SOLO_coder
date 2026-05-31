package com.chain.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.chain.infrastructure.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("gas_history")
public class GasHistory extends BaseEntity {

    private String historyId;

    private String chainType;

    private Long blockNumber;

    private BigDecimal avgGasPrice;

    private BigDecimal fastGasPrice;

    private BigDecimal standardGasPrice;

    private BigDecimal slowGasPrice;

    private BigDecimal baseFee;

    private BigDecimal priorityFee;

    private Long timestamp;
}
