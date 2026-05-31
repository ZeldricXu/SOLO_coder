package com.chain.infrastructure.chainindexer.strategy;

import com.chain.infrastructure.chainindexer.dto.BlockData;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class FullIndexingStrategy implements IndexingStrategy {

    @Override
    public String getName() {
        return "FULL";
    }

    @Override
    public Mono<Boolean> shouldIndex(BlockData blockData) {
        return Mono.just(true);
    }
}
