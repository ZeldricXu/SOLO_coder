package com.chain.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.chain.infrastructure.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chain_transaction")
public class ChainTransaction extends BaseEntity {

    private String txId;

    private String chainType;

    private Integer chainId;

    private String fromAddress;

    private String toAddress;

    private BigDecimal amount;

    private Long gasLimit;

    private BigDecimal gasPrice;

    private Long nonce;

    private String txData;

    private String signedTx;

    private String status;

    private Long blockNumber;

    private String txHash;

    private String multisigWalletId;

    private String multisigProposalId;
}
