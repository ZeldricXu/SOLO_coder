package com.web3platform.persistence.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("gas_estimate")
public class GasEstimate {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("chain_id")
    private String chainId;

    @TableField("gas_price")
    private BigDecimal gasPrice;

    @TableField("base_fee")
    private BigDecimal baseFee;

    @TableField("priority_fee")
    private BigDecimal priorityFee;

    @TableField("estimated_cost")
    private BigDecimal estimatedCost;

    @TableField("block_number")
    private Long blockNumber;

    @TableField("recorded_at")
    private LocalDateTime recordedAt;
}
