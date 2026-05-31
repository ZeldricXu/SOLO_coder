package com.web3platform.chainindexer.listener;

import com.web3platform.chaininteraction.event.NewBlockEvent;
import com.web3platform.chaininteraction.model.UnifiedBlock;
import com.web3platform.chainindexer.service.BlockParser;
import com.web3platform.chainindexer.service.BlockIndexerService;
import com.web3platform.chainindexer.model.IndexedBlock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NewBlockListener {

    private final BlockIndexerService blockIndexerService;
    private final BlockParser blockParser;

    @Async
    @EventListener
    public void handleNewBlock(NewBlockEvent event) {
        String chainId = event.getChainId();
        UnifiedBlock block = event.getBlock();

        log.debug("Received new block event: chain={}, block={}", chainId, block.getBlockNumber());

        try {
            IndexedBlock indexedBlock = blockParser.buildIndexedBlock(block);
            log.info("Processed new block {} on chain {} with {} transactions",
                    block.getBlockNumber(), chainId,
                    indexedBlock.getTransactions() != null ? indexedBlock.getTransactions().size() : 0);

        } catch (Exception e) {
            log.error("Failed to process new block event: chain={}, block={}",
                    chainId, block.getBlockNumber(), e);
        }
    }
}
