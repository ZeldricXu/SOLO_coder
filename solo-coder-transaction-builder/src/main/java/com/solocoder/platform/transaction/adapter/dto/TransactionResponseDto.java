package com.solocoder.platform.transaction.adapter.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionResponseDto {

    private String txId;
    private String chainId;
    private String from;
    private String to;
    private BigDecimal value;
    private String data;
    private Long nonce;
    private GasSettingsDto gasSettings;
    private MultisigStrategyDto multisigStrategy;
    private String status;
    private String unsignedData;
    private String signedData;
    private Integer signatureCount;
    private Boolean readyToBroadcast;
    private List<SignatureDto> signatures;
    private String broadcastData;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GasSettingsDto {
        private Long gasLimit;
        private BigDecimal gasPrice;
        private BigDecimal maxPriorityFeePerGas;
        private BigDecimal maxFeePerGas;
        private String gasType;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MultisigStrategyDto {
        private String type;
        private Integer threshold;
        private List<String> owners;
        private String walletAddress;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SignatureDto {
        private String signer;
        private String signatureData;
        private LocalDateTime signedAt;
    }
}
