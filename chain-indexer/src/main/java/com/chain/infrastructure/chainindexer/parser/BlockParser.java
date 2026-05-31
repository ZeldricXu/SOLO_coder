package com.chain.infrastructure.chainindexer.parser;

import com.chain.infrastructure.chainindexer.dto.BlockData;
import com.chain.infrastructure.persistence.entity.IndexedBlock;
import reactor.core.publisher.Mono;

public interface BlockParser {

    Mono<IndexedBlock> parse(BlockData blockData);

    String getChainType();
}
