package com.nftindexer.modules.multisig.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class ProposalCreateRequest {

    @NotBlank(message = "钱包ID不能为空")
    private String walletId;

    @NotBlank(message = "提案标题不能为空")
    private String title;

    private String description;

    @NotBlank(message = "交易数据不能为空")
    private String transactionData;

    @NotBlank(message = "目标地址不能为空")
    private String toAddress;

    @NotNull(message = "金额不能为空")
    private BigInteger value;

    private BigInteger nonce;

    @NotBlank(message = "链ID不能为空")
    private String chainId;

    private String createdBy;

    private LocalDateTime expiresAt;

    private Map<String, Object> metadata;
}
