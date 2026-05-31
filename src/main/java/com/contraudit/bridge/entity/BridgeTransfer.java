package com.contraudit.bridge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.contraudit.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bridge_transfer")
public class BridgeTransfer extends BaseEntity {

    private String transferId;

    private Long fromChainId;

    private Long toChainId;

    private String fromAddress;

    private String toAddress;

    private String tokenAddress;

    private String tokenSymbol;

    private BigDecimal amount;

    private BigDecimal fee;

    private String status;

    private String lockTxHash;

    private Long lockBlockNumber;

    private String mintTxHash;

    private Long mintBlockNumber;

    private String messageHash;

    private String proofData;

    private String errorMessage;

    private LocalDateTime expireAt;
}
