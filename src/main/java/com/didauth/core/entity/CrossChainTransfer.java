package com.didauth.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_cross_chain_transfer")
public class CrossChainTransfer extends BaseEntity {

    private String transferId;
    private String bridgeId;
    private String sourceChain;
    private String targetChain;
    private String senderAddress;
    private String recipientAddress;
    private String amount;
    private String assetSymbol;
    private String sourceTxHash;
    private String targetTxHash;
    private String messageProof;
    private String status;
    private String errorMessage;
}
