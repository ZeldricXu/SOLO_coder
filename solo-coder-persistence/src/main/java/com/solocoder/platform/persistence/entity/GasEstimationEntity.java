package com.solocoder.platform.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("gas_estimation")
public class GasEstimationEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("chain_id")
    private String chainId;

    @TableField("network")
    private String network;

    @TableField("gas_price_low")
    private BigDecimal gasPriceLow;

    @TableField("gas_price_medium")
    private BigDecimal gasPriceMedium;

    @TableField("gas_price_high")
    private BigDecimal gasPriceHigh;

    @TableField("base_fee")
    private BigDecimal baseFee;

    @TableField("priority_fee_low")
    private BigDecimal priorityFeeLow;

    @TableField("priority_fee_medium")
    private BigDecimal priorityFeeMedium;

    @TableField("priority_fee_high")
    private BigDecimal priorityFeeHigh;

    @TableField("pending_transactions")
    private Integer pendingTransactions;

    @TableField("block_gas_used")
    private Long blockGasUsed;

    @TableField("block_gas_limit")
    private Long blockGasLimit;

    @TableField("latest_block")
    private Long latestBlock;

    @TableField("timestamp")
    private Long timestamp;

    @TableField("signature")
    private String signature;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
