package com.chain.infrastructure.txbuilder.repository;

import com.chain.infrastructure.persistence.entity.ChainTransaction;
import com.chain.infrastructure.persistence.mapper.ChainTransactionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
@RequiredArgsConstructor
public class TransactionRepositoryImpl implements TransactionRepository {

    private final ChainTransactionMapper transactionMapper;

    @Override
    public Mono<ChainTransaction> save(ChainTransaction transaction) {
        return Mono.fromCallable(() -> {
            transactionMapper.insert(transaction);
            return transaction;
        });
    }

    @Override
    public Mono<ChainTransaction> findById(String txId) {
        return Mono.fromCallable(() -> transactionMapper.selectById(txId));
    }

    @Override
    public Mono<ChainTransaction> update(ChainTransaction transaction) {
        return Mono.fromCallable(() -> {
            transactionMapper.updateById(transaction);
            return transaction;
        });
    }
}
