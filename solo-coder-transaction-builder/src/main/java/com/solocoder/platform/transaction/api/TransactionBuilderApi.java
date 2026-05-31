package com.solocoder.platform.transaction.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface TransactionBuilderApi {

    BuiltTransactionResult buildTransaction(String chainId, String from, String to,
                                            BigDecimal value, String data, Long nonce,
                                            MultisigStrategy multisigStrategy);

    BuiltTransactionResult signTransaction(String txId, String signer, String privateKey);

    BuiltTransactionResult getTransaction(String txId);

    List<BuiltTransactionResult> getTransactionsByFrom(String from, int limit);

    List<BuiltTransactionResult> getTransactionsByStatus(String status, int limit);

    boolean deleteTransaction(String txId);

    interface BuiltTransactionResult {
        String getTxId();
        String getChainId();
        String getFrom();
        String getTo();
        BigDecimal getValue();
        String getUnsignedData();
        String getSignedData();
        String getStatus();
        GasSettingsResult getGasSettings();
        int getSignatureCount();
        boolean isReadyToBroadcast();
    }

    interface GasSettingsResult {
        Long getGasLimit();
        BigDecimal getGasPrice();
        BigDecimal getMaxPriorityFeePerGas();
        BigDecimal getMaxFeePerGas();
        String getGasType();
    }

    class MultisigStrategy {
        private MultisigStrategyType type;
        private Integer threshold;
        private List<String> owners;
        private String walletAddress;

        public MultisigStrategy() {}

        public MultisigStrategy(MultisigStrategyType type, Integer threshold, List<String> owners, String walletAddress) {
            this.type = type;
            this.threshold = threshold;
            this.owners = owners;
            this.walletAddress = walletAddress;
        }

        public MultisigStrategyType getType() { return type; }
        public void setType(MultisigStrategyType type) { this.type = type; }
        public Integer getThreshold() { return threshold; }
        public void setThreshold(Integer threshold) { this.threshold = threshold; }
        public List<String> getOwners() { return owners; }
        public void setOwners(List<String> owners) { this.owners = owners; }
        public String getWalletAddress() { return walletAddress; }
        public void setWalletAddress(String walletAddress) { this.walletAddress = walletAddress; }

        public enum MultisigStrategyType {
            NONE,
            THRESHOLD,
            MULTISIG_SAFE,
            GNOSIS_SAFE
        }
    }
}
