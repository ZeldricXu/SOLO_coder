package com.solocoder.platform.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("wallet_address")
public class WalletAddressEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("address_id")
    private String addressId;

    @TableField("wallet_id")
    private String walletId;

    @TableField("chain_id")
    private String chainId;

    @TableField("address")
    private String address;

    @TableField("address_format")
    private String addressFormat;

    @TableField("derivation_path")
    private String derivationPath;

    @TableField("derivation_index")
    private Integer derivationIndex;

    @TableField("change")
    private Integer change;

    @TableField("public_key")
    private String publicKey;

    @TableField("name")
    private String name;

    @TableField("labels")
    private String labels;

    @TableField("is_receive")
    private Integer isReceive;

    @TableField("is_archived")
    private Integer isArchived;

    @TableField("balance")
    private BigDecimal balance;

    @TableField("balance_updated_at")
    private LocalDateTime balanceUpdatedAt;

    @TableField("metadata")
    private String metadata;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
