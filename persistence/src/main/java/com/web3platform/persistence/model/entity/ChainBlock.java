package com.web3platform.persistence.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chain_block")
public class ChainBlock {

    @TableId(type = IdType.ASSIGN_ID)
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

    @TableField("tx_count")
    private Integer txCount;

    @TableField("raw_json")
    private String rawJson;

    @TableField("indexed_at")
    private LocalDateTime indexedAt;
}
