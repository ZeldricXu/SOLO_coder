package com.solocoder.platform.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("gas_history")
public class GasHistoryEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("chain_id")
    private String chainId;

    @TableField("block_number")
    private Long blockNumber;

    @TableField("gas_price")
    private BigDecimal gasPrice;

    @TableField("base_fee")
    private BigDecimal baseFee;

    @TableField("priority_fee")
    private BigDecimal priorityFee;

    @TableField("gas_used")
    private Long gasUsed;

    @TableField("gas_limit")
    private Long gasLimit;

    @TableField("transaction_count")
    private Integer transactionCount;

    @TableField("block_time")
    private LocalDateTime blockTime;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
