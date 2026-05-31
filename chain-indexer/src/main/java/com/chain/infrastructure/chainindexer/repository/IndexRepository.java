package com.chain.infrastructure.chainindexer.repository;

import com.chain.infrastructure.persistence.entity.IndexedBlock;
import com.chain.infrastructure.persistence.entity.IndexedTransaction;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

public interface IndexRepository {

    Mono<IndexedBlock> saveBlock(IndexedBlock block);

    Mono<List<IndexedTransaction>> saveTransactions(List<IndexedTransaction> transactions);

    Mono<IndexedBlock> findBlockByNumber(String chainType, Long blockNumber);

    Flux<IndexedTransaction> findTransactionsByBlock(String chainType, Long blockNumber);

    Flux<IndexedTransaction> findTransactionsByAddress(String chainType, String address);

    Mono<Long> findLatestBlockNumber(String chainType);
}
