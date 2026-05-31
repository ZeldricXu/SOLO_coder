package com.solocoder.platform.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("transaction_index")
public class TransactionIndexEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("chain_id")
    private String chainId;

    @TableField("tx_hash")
    private String txHash;

    @TableField("block_number")
    private Long blockNumber;

    @TableField("block_hash")
    private String blockHash;

    @TableField("transaction_index")
    private Integer transactionIndex;

    @TableField("from_address")
    private String fromAddress;

    @TableField("to_address")
    private String toAddress;

    @TableField("contract_address")
    private String contractAddress;

    @TableField("tx_type")
    private String txType;

    @TableField("value")
    private BigDecimal value;

    @TableField("gas_price")
    private BigDecimal gasPrice;

    @TableField("gas_limit")
    private Long gasLimit;

    @TableField("gas_used")
    private Long gasUsed;

    @TableField("fee")
    private BigDecimal fee;

    @TableField("status")
    private String status;

    @TableField("method_id")
    private String methodId;

    @TableField("method_name")
    private String methodName;

    @TableField("timestamp")
    private Long timestamp;

    @TableField("input_data")
    private String inputData;

    @TableField("events")
    private String events;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
