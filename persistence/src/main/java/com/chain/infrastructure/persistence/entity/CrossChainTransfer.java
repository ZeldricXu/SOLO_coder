package com.chain.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.chain.infrastructure.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cross_chain_transfer")
public class CrossChainTransfer extends BaseEntity {

    private String transferId;

    private String sourceChain;

    private String targetChain;

    private String sourceAddress;

    private String targetAddress;

    private String tokenAddress;

    private BigDecimal amount;

    private BigDecimal fee;

    private String sourceTxHash;

    private String targetTxHash;

    private String status;

    private String messageProof;

    private String lockTransactionId;

    private String mintTransactionId;

    private LocalDateTime confirmedAt;

    private LocalDateTime expiresAt;
}
