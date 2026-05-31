package com.chain.infrastructure.chainindexer.parser;

import com.chain.infrastructure.chainindexer.dto.BlockData;
import com.chain.infrastructure.chainindexer.dto.TransactionData;
import com.chain.infrastructure.persistence.entity.IndexedTransaction;
import reactor.core.publisher.Mono;

public interface TransactionParser {

    Mono<IndexedTransaction> parse(BlockData blockData, TransactionData txData);

    String getChainType();
}
