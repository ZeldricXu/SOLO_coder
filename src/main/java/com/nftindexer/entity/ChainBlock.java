package com.nftindexer.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigInteger;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chain_block")
public class ChainBlock extends BaseEntity {

    private String blockId;
    private String chainId;
    private Integer blockNumber;
    private String blockHash;
    private String parentHash;
    private String miner;
    private BigInteger difficulty;
    private BigInteger totalDifficulty;
    private BigInteger gasLimit;
    private BigInteger gasUsed;
    private LocalDateTime blockTime;
    private Integer transactionCount;
    private Integer logCount;
    private String status;
    private LocalDateTime indexedAt;
}
