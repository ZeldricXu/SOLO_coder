package com.chainetl.modules.multisig.model;

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
@TableName("multisig_proposals")
public class MultisigProposal {

    @TableId(type = IdType.INPUT)
    private String proposalId;

    private String walletId;

    private String chainId;

    private String transactionData;

    private Integer requiredSignatures;

    private Integer currentSignatures;

    private String status;

    private String proposer;

    private Instant createdAt;

    private Instant executedAt;

    private Instant expiresAt;
}
