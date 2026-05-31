package com.solocoder.platform.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("block_data")
public class BlockDataEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("chain_id")
    private String chainId;

    @TableField("block_number")
    private Long blockNumber;

    @TableField("block_hash")
    private String blockHash;

    @TableField("parent_hash")
    private String parentHash;

    @TableField("timestamp")
    private Long timestamp;

    @TableField("miner")
    private String miner;

    @TableField("difficulty")
    private String difficulty;

    @TableField("total_difficulty")
    private String totalDifficulty;

    @TableField("gas_used")
    private Long gasUsed;

    @TableField("gas_limit")
    private Long gasLimit;

    @TableField("size")
    private Long size;

    @TableField("transaction_count")
    private Integer transactionCount;

    @TableField("base_fee_per_gas")
    private BigDecimal baseFeePerGas;

    @TableField("extra_data")
    private String extraData;

    @TableField("raw_data")
    private String rawData;

    @TableField("index_status")
    private String indexStatus;

    @TableField("indexed_at")
    private LocalDateTime indexedAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
