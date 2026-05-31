package com.solocoder.platform.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("hd_wallet")
public class HdWalletEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("wallet_id")
    private String walletId;

    @TableField("name")
    private String name;

    @TableField("mnemonic_encrypted")
    private String mnemonicEncrypted;

    @TableField("seed_encrypted")
    private String seedEncrypted;

    @TableField("passphrase_protected")
    private Integer passphraseProtected;

    @TableField("derivation_path")
    private String derivationPath;

    @TableField("curve_type")
    private String curveType;

    @TableField("address_count")
    private Integer addressCount;

    @TableField("last_derived_index")
    private Integer lastDerivedIndex;

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
