package com.nftindexer.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chain_indexer_state")
public class ChainIndexerState extends BaseEntity {

    private String stateId;
    private String chainId;
    private String indexerName;
    private Integer lastIndexedBlock;
    private Integer lastFinalizedBlock;
    private String status;
    private LocalDateTime lastIndexedAt;
    private LocalDateTime lastFinalizedAt;
    private Long totalBlocksIndexed;
    private Long totalTransactionsIndexed;
    private Long totalLogsIndexed;
    private String errorDetail;
}
