package com.web3platform.persistence.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("cross_chain_lock")
public class CrossChainLock {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("source_chain")
    private String sourceChain;

    @TableField("target_chain")
    private String targetChain;

    @TableField("tx_hash")
    private String txHash;

    @TableField("lock_amount")
    private BigDecimal lockAmount;

    @TableField("lock_status")
    private String lockStatus;

    @TableField("locker_address")
    private String lockerAddress;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
