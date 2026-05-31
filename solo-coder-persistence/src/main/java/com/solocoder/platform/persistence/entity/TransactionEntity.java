package com.solocoder.platform.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("built_transaction")
public class TransactionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("tx_id")
    private String txId;

    @TableField("chain_id")
    private String chainId;

    @TableField("from_address")
    private String fromAddress;

    @TableField("to_address")
    private String toAddress;

    @TableField("amount")
    private BigDecimal amount;

    @TableField("data")
    private String data;

    @TableField("nonce")
    private Long nonce;

    @TableField("gas_limit")
    private Long gasLimit;

    @TableField("gas_price")
    private BigDecimal gasPrice;

    @TableField("max_priority_fee")
    private BigDecimal maxPriorityFee;

    @TableField("max_fee")
    private BigDecimal maxFee;

    @TableField("gas_type")
    private String gasType;

    @TableField("multisig_type")
    private String multisigType;

    @TableField("multisig_threshold")
    private Integer multisigThreshold;

    @TableField("multisig_owners")
    private String multisigOwners;

    @TableField("multisig_wallet")
    private String multisigWallet;

    @TableField("status")
    private String status;

    @TableField("unsigned_data")
    private String unsignedData;

    @TableField("signed_data")
    private String signedData;

    @TableField("signatures")
    private String signatures;

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
