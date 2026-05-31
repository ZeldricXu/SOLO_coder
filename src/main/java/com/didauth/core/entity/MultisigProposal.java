package com.didauth.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_multisig_proposal")
public class MultisigProposal extends BaseEntity {

    private String proposalId;
    private String walletId;
    private String chainType;
    private String transactionData;
    private String toAddress;
    private String value;
    private String data;
    private String nonce;
    private Integer threshold;
    private String signatures;
    private String signerAddresses;
    private Integer signedCount;
    private String status;
    private String txHash;
    private String errorMessage;
}
