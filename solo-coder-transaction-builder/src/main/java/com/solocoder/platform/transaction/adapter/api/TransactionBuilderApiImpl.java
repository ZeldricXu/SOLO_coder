package com.solocoder.platform.transaction.adapter.api;

import com.solocoder.platform.transaction.api.TransactionBuilderApi;
import com.solocoder.platform.transaction.application.service.TransactionApplicationService;
import com.solocoder.platform.transaction.domain.model.BuiltTransaction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class TransactionBuilderApiImpl implements TransactionBuilderApi {

    private final TransactionApplicationService transactionApplicationService;

    @Override
    public BuiltTransactionResult buildTransaction(String chainId, String from, String to,
                                                   BigDecimal value, String data, Long nonce,
                                                   MultisigStrategy multisigStrategy) {
        BuiltTransaction.MultisigStrategy domainStrategy = toDomainMultisigStrategy(multisigStrategy);
        BuiltTransaction transaction = transactionApplicationService.buildTransaction(
                chainId, from, to, value, data, nonce, domainStrategy);
        return toApiResult(transaction);
    }

    @Override
    public BuiltTransactionResult signTransaction(String txId, String signer, String privateKey) {
        BuiltTransaction transaction = transactionApplicationService.signTransaction(txId, signer, privateKey);
        return toApiResult(transaction);
    }

    @Override
    public BuiltTransactionResult getTransaction(String txId) {
        BuiltTransaction transaction = transactionApplicationService.getTransaction(txId);
        return toApiResult(transaction);
    }

    @Override
    public List<BuiltTransactionResult> getTransactionsByFrom(String from, int limit) {
        return transactionApplicationService.getTransactionsByFrom(from, limit).stream()
                .map(this::toApiResult)
                .collect(Collectors.toList());
    }

    @Override
    public List<BuiltTransactionResult> getTransactionsByStatus(String status, int limit) {
        return transactionApplicationService.getTransactionsByStatus(
                BuiltTransaction.TransactionStatus.valueOf(status.toUpperCase()), limit).stream()
                .map(this::toApiResult)
                .collect(Collectors.toList());
    }

    @Override
    public boolean deleteTransaction(String txId) {
        return transactionApplicationService.deleteTransaction(txId);
    }

    private BuiltTransaction.MultisigStrategy toDomainMultisigStrategy(MultisigStrategy api) {
        if (api == null || api.getType() == null || api.getType() == MultisigStrategy.MultisigStrategyType.NONE) {
            return null;
        }
        return BuiltTransaction.MultisigStrategy.builder()
                .type(BuiltTransaction.MultisigStrategy.MultisigStrategyType.valueOf(api.getType().name()))
                .threshold(api.getThreshold())
                .owners(api.getOwners())
                .walletAddress(api.getWalletAddress())
                .build();
    }

    private BuiltTransactionResult toApiResult(BuiltTransaction domain) {
        return new BuiltTransactionResult() {
            @Override
            public String getTxId() { return domain.getTxId(); }

            @Override
            public String getChainId() { return domain.getChainId(); }

            @Override
            public String getFrom() { return domain.getFrom(); }

            @Override
            public String getTo() { return domain.getTo(); }

            @Override
            public BigDecimal getValue() { return domain.getValue(); }

            @Override
            public String getUnsignedData() { return domain.getUnsignedData(); }

            @Override
            public String getSignedData() { return domain.getSignedData(); }

            @Override
            public String getStatus() { return domain.getStatus() != null ? domain.getStatus().name() : null; }

            @Override
            public GasSettingsResult getGasSettings() {
                if (domain.getGasSettings() == null) return null;
                return new GasSettingsResult() {
                    @Override
                    public Long getGasLimit() { return domain.getGasSettings().getGasLimit(); }
                    @Override
                    public BigDecimal getGasPrice() { return domain.getGasSettings().getGasPrice(); }
                    @Override
                    public BigDecimal getMaxPriorityFeePerGas() { return domain.getGasSettings().getMaxPriorityFeePerGas(); }
                    @Override
                    public BigDecimal getMaxFeePerGas() { return domain.getGasSettings().getMaxFeePerGas(); }
                    @Override
                    public String getGasType() { return domain.getGasSettings().getGasType() != null ? domain.getGasSettings().getGasType().name() : null; }
                };
            }

            @Override
            public int getSignatureCount() { return domain.getSignatureCount(); }

            @Override
            public boolean isReadyToBroadcast() { return domain.isReadyToBroadcast(); }
        };
    }
}
