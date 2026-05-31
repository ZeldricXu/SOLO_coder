package com.chain.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.chain.infrastructure.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("indexed_transaction")
public class IndexedTransaction extends BaseEntity {

    private String txId;

    private String chainType;

    private Long blockNumber;

    private String txHash;

    private Integer txIndex;

    private String fromAddress;

    private String toAddress;

    private BigDecimal value;

    private BigDecimal gasPrice;

    private Long gasUsed;

    private String inputData;

    private Integer status;

    private String contractAddress;
}
