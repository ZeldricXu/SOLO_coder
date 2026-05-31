package com.didauth.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_transaction")
public class ChainTransaction extends BaseEntity {

    private String txId;
    private String chainType;
    private String fromAddress;
    private String toAddress;
    private String value;
    private String gasPrice;
    private String gasLimit;
    private String nonce;
    private String data;
    private String signedTx;
    private String txHash;
    private String signType;
    private String multisigWalletId;
    private String status;
    private String errorMessage;
}
