package com.solocoder.platform.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("multisig_wallet")
public class MultisigWalletEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("wallet_id")
    private String walletId;

    @TableField("chain_id")
    private String chainId;

    @TableField("wallet_address")
    private String walletAddress;

    @TableField("wallet_type")
    private String walletType;

    @TableField("name")
    private String name;

    @TableField("threshold")
    private Integer threshold;

    @TableField("owners")
    private String owners;

    @TableField("owner_count")
    private Integer ownerCount;

    @TableField("version")
    private String version;

    @TableField("nonce")
    private Long nonce;

    @TableField("creation_tx_hash")
    private String creationTxHash;

    @TableField("creation_block")
    private Long creationBlock;

    @TableField("status")
    private String status;

    @TableField("metadata")
    private String metadata;

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
