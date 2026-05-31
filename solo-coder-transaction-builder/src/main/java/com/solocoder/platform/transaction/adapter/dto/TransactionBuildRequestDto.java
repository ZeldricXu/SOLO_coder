package com.solocoder.platform.transaction.adapter.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class TransactionBuildRequestDto {

    @NotBlank(message = "链ID不能为空")
    private String chainId;

    @NotBlank(message = "发送地址不能为空")
    private String from;

    @NotBlank(message = "接收地址不能为空")
    private String to;

    private BigDecimal value;

    private String data;

    private Long nonce;

    private Boolean optimizeGas = false;

    private MultisigStrategyDto multisigStrategy;

    @Data
    public static class MultisigStrategyDto {
        private String type;
        private Integer threshold;
        private List<String> owners;
        private String walletAddress;
    }
}
