package com.solocoder.platform.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("transaction_record")
public class TransactionRecordEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("tx_hash")
    private String txHash;

    @TableField("chain_id")
    private String chainId;

    @TableField("from_address")
    private String fromAddress;

    @TableField("to_address")
    private String toAddress;

    @TableField("value")
    private BigDecimal value;

    @TableField("gas_limit")
    private Long gasLimit;

    @TableField("gas_price")
    private BigDecimal gasPrice;

    @TableField("gas_used")
    private Long gasUsed;

    @TableField("nonce")
    private Long nonce;

    @TableField("data")
    private String data;

    @TableField("status")
    private String status;

    @TableField("block_number")
    private Long blockNumber;

    @TableField("block_hash")
    private String blockHash;

    @TableField("transaction_index")
    private Integer transactionIndex;

    @TableField("signature")
    private String signature;

    @TableField("signers")
    private String signers;

    @TableField("multisig_strategy")
    private String multisigStrategy;

    @TableField("error_message")
    private String errorMessage;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
