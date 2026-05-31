package com.solocoder.platform.transaction.application.service;

import com.solocoder.platform.transaction.domain.model.BuiltTransaction;
import com.solocoder.platform.transaction.domain.repository.BuiltTransactionRepository;
import com.solocoder.platform.transaction.domain.service.SignatureManager;
import com.solocoder.platform.transaction.domain.service.TransactionBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionApplicationService {

    private final TransactionBuilder transactionBuilder;
    private final SignatureManager signatureManager;
    private final BuiltTransactionRepository builtTransactionRepository;

    @Transactional(rollbackFor = Exception.class)
    public BuiltTransaction buildTransaction(String chainId, String from, String to,
                                           BigDecimal value, String data, Long nonce,
                                           BuiltTransaction.MultisigStrategy multisigStrategy) {
        BuiltTransaction transaction = transactionBuilder.buildTransaction(
                chainId, from, to, value, data, nonce, multisigStrategy);
        return builtTransactionRepository.save(transaction);
    }

    @Transactional(rollbackFor = Exception.class)
    public BuiltTransaction buildOptimizedTransaction(String chainId, String from, String to,
                                                  BigDecimal value, String data, Long nonce,
                                                  BuiltTransaction.MultisigStrategy multisigStrategy) {
        BuiltTransaction transaction = buildTransaction(chainId, from, to, value, data, nonce, multisigStrategy);
        transaction = transactionBuilder.applyGasOptimization(transaction);
        return builtTransactionRepository.save(transaction);
    }

    @Transactional(rollbackFor = Exception.class)
    public BuiltTransaction signTransaction(String txId, String signer, String privateKey) {
        BuiltTransaction transaction = builtTransactionRepository.findByTxId(txId)
                .orElseThrow(() -> new IllegalArgumentException("交易不存在: " + txId));

        transaction = signatureManager.signTransaction(transaction, signer, privateKey);
        return builtTransactionRepository.save(transaction);
    }

    public BuiltTransaction getTransaction(String txId) {
        return builtTransactionRepository.findByTxId(txId)
                .orElseThrow(() -> new IllegalArgumentException("交易不存在: " + txId));
    }

    public List<BuiltTransaction> getTransactionsByChain(String chainId, int limit) {
        return builtTransactionRepository.findByChainId(chainId, limit);
    }

    public List<BuiltTransaction> getTransactionsByFrom(String from, int limit) {
        return builtTransactionRepository.findByFrom(from, limit);
    }

    public List<BuiltTransaction> getTransactionsByStatus(BuiltTransaction.TransactionStatus status, int limit) {
        return builtTransactionRepository.findByStatus(status, limit);
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean updateStatus(String txId, BuiltTransaction.TransactionStatus status) {
        return builtTransactionRepository.updateStatus(txId, status);
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean deleteTransaction(String txId) {
        return builtTransactionRepository.deleteByTxId(txId);
    }

    public boolean isReadyToBroadcast(String txId) {
        BuiltTransaction transaction = getTransaction(txId);
        return transaction.isReadyToBroadcast();
    }

    public String getBroadcastData(String txId) {
        BuiltTransaction transaction = getTransaction(txId);
        if (!transaction.isReadyToBroadcast()) {
            throw new IllegalStateException("交易未准备好广播");
        }

        if (transaction.getMultisigStrategy() != null &&
                transaction.getMultisigStrategy().getType() != BuiltTransaction.MultisigStrategy.MultisigStrategyType.NONE) {
            return signatureManager.getMultisigTransactionData(transaction);
        }
        return transaction.getSignedData();
    }
}
