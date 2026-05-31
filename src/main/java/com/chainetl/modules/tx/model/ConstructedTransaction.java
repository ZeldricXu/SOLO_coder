package com.chainetl.modules.tx.model;

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
@TableName("constructed_transactions")
public class ConstructedTransaction {

    @TableId(type = IdType.INPUT)
    private String txId;

    private String chainId;

    private String fromAddress;

    private String toAddress;

    private BigInteger value;

    private Long gasLimit;

    private Long gasPrice;

    private Long nonce;

    private String data;

    private String signedTx;

    private String multisigWalletId;

    private String status;

    private String txHash;

    private Instant submittedAt;

    private Instant createdAt;
}
