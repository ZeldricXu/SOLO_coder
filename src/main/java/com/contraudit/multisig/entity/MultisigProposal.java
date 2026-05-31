package com.contraudit.multisig.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.contraudit.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("multisig_proposal")
public class MultisigProposal extends BaseEntity {

    private String walletId;

    private String proposalType;

    private String title;

    private String description;

    private String toAddress;

    private BigDecimal value;

    private String data;

    private Long nonce;

    private Long gasLimit;

    private BigDecimal gasPrice;

    private String status;

    private Integer requiredConfirmations;

    private Integer currentConfirmations;

    private LocalDateTime expireAt;

    private LocalDateTime executedAt;

    private String txHash;

    private String creatorAddress;
}
