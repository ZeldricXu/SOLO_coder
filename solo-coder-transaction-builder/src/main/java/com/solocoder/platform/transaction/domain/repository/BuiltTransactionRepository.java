package com.solocoder.platform.transaction.domain.repository;

import com.solocoder.platform.transaction.domain.model.BuiltTransaction;

import java.util.List;
import java.util.Optional;

public interface BuiltTransactionRepository {

    BuiltTransaction save(BuiltTransaction transaction);

    Optional<BuiltTransaction> findByTxId(String txId);

    List<BuiltTransaction> findByChainId(String chainId, int limit);

    List<BuiltTransaction> findByFrom(String from, int limit);

    List<BuiltTransaction> findByStatus(BuiltTransaction.TransactionStatus status, int limit);

    boolean updateStatus(String txId, BuiltTransaction.TransactionStatus status);

    boolean deleteByTxId(String txId);
}
