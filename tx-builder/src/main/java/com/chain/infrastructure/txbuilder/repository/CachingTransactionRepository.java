package com.chain.infrastructure.txbuilder.repository;

import com.chain.infrastructure.persistence.entity.ChainTransaction;
import com.chain.infrastructure.txbuilder.cache.MultilevelCache;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
@Primary
@RequiredArgsConstructor
public class CachingTransactionRepository implements TransactionRepository {

    private final TransactionRepositoryImpl delegate;
    private final MultilevelCache<String, ChainTransaction> transactionCache;

    @Override
    public Mono<ChainTransaction> save(ChainTransaction transaction) {
        return delegate.save(transaction)
                .flatMap(saved -> transactionCache.put(saved.getTxId(), saved));
    }

    @Override
    public Mono<ChainTransaction> findById(String txId) {
        return transactionCache.get(txId)
                .switchIfEmpty(
                        delegate.findById(txId)
                                .flatMap(tx -> transactionCache.put(txId, tx))
                );
    }

    @Override
    public Mono<ChainTransaction> update(ChainTransaction transaction) {
        return delegate.update(transaction)
                .flatMap(updated -> transactionCache.evict(updated.getTxId())
                        .then(transactionCache.put(updated.getTxId(), updated)));
    }
}
