package com.chainetl.modules.indexer.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigInteger;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("indexed_transactions")
public class IndexedTransaction {

    @TableId(type = IdType.INPUT)
    private String txId;

    private String chainId;

    private Long blockNumber;

    private String txHash;

    private String fromAddress;

    private String toAddress;

    private BigInteger value;

    private Long gasUsed;

    private Long gasPrice;

    private String status;

    private String inputData;

    private Instant indexedAt;
}
