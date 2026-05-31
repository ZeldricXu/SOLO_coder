package com.chain.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.chain.infrastructure.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("multisig_proposal")
public class MultisigProposal extends BaseEntity {

    private String proposalId;

    private String walletId;

    private String proposer;

    private String title;

    private String description;

    private String txData;

    private String status;

    private String signatures;

    private Integer signedCount;

    private Integer threshold;

    private LocalDateTime executedAt;

    private String txHash;

    private LocalDateTime expiresAt;
}
