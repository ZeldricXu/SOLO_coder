package com.nftindexer.modules.indexer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
public class BlockIndexRequest {

    @NotBlank(message = "链ID不能为空")
    private String chainId;

    @NotNull(message = "区块号不能为空")
    private Integer blockNumber;

    @NotBlank(message = "区块哈希不能为空")
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

    private List<TransactionIndexRequest> transactions;

    private String rawBlockData;

    private Map<String, Object> metadata;
}
