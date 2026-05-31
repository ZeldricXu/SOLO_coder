package com.chain.infrastructure.txbuilder.repository;

import com.chain.infrastructure.persistence.entity.ChainTransaction;
import reactor.core.publisher.Mono;

public interface TransactionRepository {

    Mono<ChainTransaction> save(ChainTransaction transaction);

    Mono<ChainTransaction> findById(String txId);

    Mono<ChainTransaction> update(ChainTransaction transaction);
}
