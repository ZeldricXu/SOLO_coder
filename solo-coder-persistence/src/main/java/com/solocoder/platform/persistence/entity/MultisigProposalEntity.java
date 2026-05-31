package com.solocoder.platform.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("multisig_proposal")
public class MultisigProposalEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("proposal_id")
    private String proposalId;

    @TableField("wallet_id")
    private String walletId;

    @TableField("chain_id")
    private String chainId;

    @TableField("proposal_type")
    private String proposalType;

    @TableField("title")
    private String title;

    @TableField("description")
    private String description;

    @TableField("to_address")
    private String toAddress;

    @TableField("value")
    private BigDecimal value;

    @TableField("data")
    private String data;

    @TableField("operation")
    private Integer operation;

    @TableField("safe_tx_gas")
    private Long safeTxGas;

    @TableField("base_gas")
    private Long baseGas;

    @TableField("gas_price")
    private BigDecimal gasPrice;

    @TableField("gas_token")
    private String gasToken;

    @TableField("refund_receiver")
    private String refundReceiver;

    @TableField("nonce")
    private Long nonce;

    @TableField("safe_tx_hash")
    private String safeTxHash;

    @TableField("signatures")
    private String signatures;

    @TableField("signature_count")
    private Integer signatureCount;

    @TableField("threshold")
    private Integer threshold;

    @TableField("status")
    private String status;

    @TableField("tx_hash")
    private String txHash;

    @TableField("executed_at")
    private LocalDateTime executedAt;

    @TableField("expire_time")
    private LocalDateTime expireTime;

    @TableField("created_by")
    private String createdBy;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
