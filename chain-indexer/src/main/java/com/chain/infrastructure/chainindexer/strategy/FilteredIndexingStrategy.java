package com.chain.infrastructure.chainindexer.strategy;

import com.chain.infrastructure.chainindexer.dto.BlockData;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class FilteredIndexingStrategy implements IndexingStrategy {

    @Override
    public String getName() {
        return "FILTERED";
    }

    @Override
    public Mono<Boolean> shouldIndex(BlockData blockData) {
        return Mono.just(blockData.getTxCount() != null && blockData.getTxCount() > 0);
    }
}
