package com.chain.infrastructure.chainindexer.strategy;

import com.chain.infrastructure.chainindexer.dto.BlockData;
import reactor.core.publisher.Mono;

public interface IndexingStrategy {

    String getName();

    Mono<Boolean> shouldIndex(BlockData blockData);

    default boolean shouldIndexTransactions() {
        return true;
    }

    default int getBatchSize() {
        return 100;
    }
}
