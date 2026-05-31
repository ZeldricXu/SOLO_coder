package com.solocoder.platform.transaction.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BuiltTransaction {

    private Long id;
    private String txId;
    private String chainId;
    private String from;
    private String to;
    private BigDecimal value;
    private String data;
    private Long nonce;
    private GasSettings gasSettings;
    private MultisigStrategy multisigStrategy;
    private TransactionStatus status;
    private String unsignedData;
    private String signedData;
    private List<Signature> signatures;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public enum TransactionStatus {
        PENDING,
        SIGNED,
        PARTIALLY_SIGNED,
        READY_TO_BROADCAST,
        BROADCASTED,
        CONFIRMED,
        FAILED
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GasSettings {
        private Long gasLimit;
        private BigDecimal gasPrice;
        private BigDecimal maxPriorityFeePerGas;
        private BigDecimal maxFeePerGas;
        private GasType gasType;

        public enum GasType {
            LEGACY,
            EIP1559,
            DYNAMIC
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MultisigStrategy {
        private MultisigStrategyType type;
        private Integer threshold;
        private List<String> owners;
        private String walletAddress;

        public enum MultisigStrategyType {
            NONE,
            THRESHOLD,
            MULTISIG_SAFE,
            GNOSIS_SAFE
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Signature {
        private String signer;
        private String signatureData;
        private LocalDateTime signedAt;
    }

    public boolean isReadyToBroadcast() {
        if (multisigStrategy == null || multisigStrategy.getType() == MultisigStrategy.MultisigStrategyType.NONE) {
            return signedData != null && status == TransactionStatus.SIGNED;
        }
        int signatureCount = signatures != null ? signatures.size() : 0;
        return signatureCount >= multisigStrategy.getThreshold();
    }

    public int getSignatureCount() {
        return signatures != null ? signatures.size() : 0;
    }

    public void addSignature(Signature signature) {
        if (signatures == null) {
            signatures = new ArrayList<>();
        }
        signatures.add(signature);
    }
}
