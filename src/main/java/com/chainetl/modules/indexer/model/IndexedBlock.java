package com.chainetl.modules.indexer.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("indexed_blocks")
public class IndexedBlock {

    @TableId(type = IdType.INPUT)
    private String blockId;

    private String chainId;

    private Long blockNumber;

    private String blockHash;

    private String parentHash;

    private Instant timestamp;

    private Integer transactionCount;

    private String rawData;

    private Instant indexedAt;
}
