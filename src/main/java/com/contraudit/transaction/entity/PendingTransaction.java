package com.contraudit.transaction.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.contraudit.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("pending_transaction")
public class PendingTransaction extends BaseEntity {

    private String chainType;

    private String fromAddress;

    private String toAddress;

    private BigDecimal value;

    private String data;

    private Long nonce;

    private Long gasLimit;

    private BigDecimal gasPrice;

    private BigDecimal maxPriorityFee;

    private BigDecimal maxFeePerGas;

    private Integer txType;

    private String signature;

    private String signedTx;

    private String txHash;

    private String status;

    private Long blockNumber;

    private String errorMessage;

    private String multisigWalletId;

    private String templateId;
}
