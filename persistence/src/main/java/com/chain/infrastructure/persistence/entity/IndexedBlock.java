package com.chain.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.chain.infrastructure.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("indexed_block")
public class IndexedBlock extends BaseEntity {

    private String blockId;

    private String chainType;

    private Integer chainId;

    private Long blockNumber;

    private String blockHash;

    private String parentHash;

    private Long timestamp;

    private String miner;

    private BigDecimal difficulty;

    private Long gasUsed;

    private Long gasLimit;

    private Integer txCount;

    private String rawData;

    private LocalDateTime indexedAt;
}
