package com.web3platform.persistence.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("cross_chain_mint")
public class CrossChainMint {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("lock_id")
    private Long lockId;

    @TableField("target_chain")
    private String targetChain;

    @TableField("mint_tx_hash")
    private String mintTxHash;

    @TableField("mint_amount")
    private BigDecimal mintAmount;

    @TableField("mint_status")
    private String mintStatus;

    @TableField("minter_address")
    private String minterAddress;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
