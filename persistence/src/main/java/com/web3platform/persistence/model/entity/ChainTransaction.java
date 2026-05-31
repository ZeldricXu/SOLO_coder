package com.web3platform.persistence.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("chain_transaction")
public class ChainTransaction {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("chain_id")
    private String chainId;

    @TableField("block_number")
    private Long blockNumber;

    @TableField("tx_hash")
    private String txHash;

    @TableField("from_address")
    private String fromAddress;

    @TableField("to_address")
    private String toAddress;

    @TableField("value")
    private BigDecimal value;

    @TableField("gas_used")
    private Long gasUsed;

    @TableField("status")
    private Integer status;

    @TableField("raw_json")
    private String rawJson;

    @TableField("indexed_at")
    private LocalDateTime indexedAt;
}
