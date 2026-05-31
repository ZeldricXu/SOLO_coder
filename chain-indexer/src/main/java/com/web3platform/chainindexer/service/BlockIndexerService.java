package com.web3platform.chainindexer.service;

import com.web3platform.chaininteraction.event.NewBlockEvent;
import com.web3platform.chaininteraction.model.UnifiedBlock;
import com.web3platform.chaininteraction.service.ChainClient;
import com.web3platform.chaininteraction.service.ChainClientFactory;
import com.web3platform.chainindexer.config.IndexerProperties;
import com.web3platform.chainindexer.model.IndexedBlock;
import com.web3platform.chainindexer.model.IndexedTransaction;
import com.web3platform.persistence.mapper.ChainBlockMapper;
import com.web3platform.persistence.mapper.ChainTransactionMapper;
import com.web3platform.persistence.model.entity.ChainBlock;
import com.web3platform.persistence.model.entity.ChainTransaction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class BlockIndexerService {

    private final ChainClientFactory chainClientFactory;
    private final ChainBlockMapper chainBlockMapper;
    private final ChainTransactionMapper chainTransactionMapper;
    private final BlockParser blockParser;
    private final IndexerProperties indexerProperties;
    private final ApplicationEventPublisher eventPublisher;

    private final Map<String, AtomicBoolean> realtimeIndexingFlags = new ConcurrentHashMap<>();
    private final ExecutorService indexerExecutor = Executors.newCachedThreadPool();

    public IndexedBlock indexBlock(String chainId, long blockNumber) {
        log.info("Indexing block {} on chain {}", blockNumber, chainId);

        try {
            ChainClient chainClient = chainClientFactory.getClient(chainId);
            UnifiedBlock unifiedBlock = chainClient.getBlockByNumber(chainId, blockNumber);

            if (unifiedBlock == null) {
                throw new RuntimeException("Block not found: " + blockNumber);
            }

            IndexedBlock indexedBlock = blockParser.buildIndexedBlock(unifiedBlock);

            saveBlock(indexedBlock);

            eventPublisher.publishEvent(new NewBlockEvent(this, chainId, unifiedBlock));

            log.info("Successfully indexed block {} on chain {}", blockNumber, chainId);
            return indexedBlock;
        } catch (Exception e) {
            log.error("Failed to index block {} on chain {}", blockNumber, chainId, e);
            throw new RuntimeException("Failed to index block", e);
        }
    }

    public void indexRange(String chainId, long fromBlock, long toBlock) {
        log.info("Starting range index from block {} to {} on chain {}", fromBlock, toBlock, chainId);

        long batchSize = indexerProperties.getBatchSize();
        long current = fromBlock;

        while (current <= toBlock) {
            long batchEnd = Math.min(current + batchSize - 1, toBlock);
            indexBatch(chainId, current, batchEnd);
            current = batchEnd + 1;
        }

        log.info("Completed range index from block {} to {} on chain {}", fromBlock, toBlock, chainId);
    }

    @Async
    public void indexRangeAsync(String chainId, long fromBlock, long toBlock,
                                java.util.function.Consumer<Integer> progressCallback) {
        long total = toBlock - fromBlock + 1;
        long batchSize = indexerProperties.getBatchSize();
        long current = fromBlock;

        while (current <= toBlock) {
            long batchEnd = Math.min(current + batchSize - 1, toBlock);
            indexBatch(chainId, current, batchEnd);

            int progress = (int) ((batchEnd - fromBlock + 1) * 100 / total);
            if (progressCallback != null) {
                progressCallback.accept(progress);
            }

            current = batchEnd + 1;
        }

        if (progressCallback != null) {
            progressCallback.accept(100);
        }
    }

    private void indexBatch(String chainId, long startBlock, long endBlock) {
        List<ChainBlock> blocks = new ArrayList<>();
        List<ChainTransaction> transactions = new ArrayList<>();

        ChainClient chainClient = chainClientFactory.getClient(chainId);

        for (long blockNum = startBlock; blockNum <= endBlock; blockNum++) {
            try {
                UnifiedBlock unifiedBlock = chainClient.getBlockByNumber(chainId, blockNum);
                if (unifiedBlock != null) {
                    IndexedBlock indexedBlock = blockParser.buildIndexedBlock(unifiedBlock);
                    blocks.add(indexedBlock.getChainBlock());

                    for (IndexedTransaction indexedTx : indexedBlock.getTransactions()) {
                        transactions.add(indexedTx.getChainTransaction());
                    }

                    eventPublisher.publishEvent(new NewBlockEvent(this, chainId, unifiedBlock));
                }
            } catch (Exception e) {
                log.error("Failed to index block {} in batch", blockNum, e);
            }
        }

        if (!blocks.isEmpty()) {
            blocks.forEach(chainBlockMapper::insert);
        }
        if (!transactions.isEmpty()) {
            transactions.forEach(chainTransactionMapper::insert);
        }

        log.info("Indexed batch from block {} to {}: {} blocks, {} transactions",
                startBlock, endBlock, blocks.size(), transactions.size());
    }

    public void startRealtimeIndexing(String chainId) {
        AtomicBoolean flag = realtimeIndexingFlags.computeIfAbsent(chainId, k -> new AtomicBoolean(false));

        if (flag.compareAndSet(false, true)) {
            log.info("Starting realtime indexing for chain {}", chainId);
            indexerExecutor.submit(() -> runRealtimeIndexing(chainId, flag));
        } else {
            log.info("Realtime indexing already running for chain {}", chainId);
        }
    }

    public void stopRealtimeIndexing(String chainId) {
        AtomicBoolean flag = realtimeIndexingFlags.get(chainId);
        if (flag != null) {
            flag.set(false);
            log.info("Stopped realtime indexing for chain {}", chainId);
        }
    }

    private void runRealtimeIndexing(String chainId, AtomicBoolean running) {
        ChainClient chainClient = chainClientFactory.getClient(chainId);
        long lastIndexedBlock = chainClient.getLatestBlockNumber(chainId) - 1;

        while (running.get()) {
            try {
                long latestBlock = chainClient.getLatestBlockNumber(chainId);

                while (lastIndexedBlock < latestBlock && running.get()) {
                    long nextBlock = lastIndexedBlock + 1;
                    try {
                        indexBlock(chainId, nextBlock);
                        lastIndexedBlock = nextBlock;
                    } catch (Exception e) {
                        log.error("Failed to index block {} in realtime", nextBlock, e);
                        Thread.sleep(indexerProperties.getRetryInterval());
                    }
                }

                Thread.sleep(indexerProperties.getRealtimePollInterval());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in realtime indexing loop for chain {}", chainId, e);
                try {
                    Thread.sleep(indexerProperties.getRetryInterval());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        log.info("Realtime indexing thread exited for chain {}", chainId);
    }

    private void saveBlock(IndexedBlock indexedBlock) {
        ChainBlock chainBlock = indexedBlock.getChainBlock();
        chainBlock.setIndexedAt(LocalDateTime.now());
        chainBlockMapper.insert(chainBlock);

        for (IndexedTransaction indexedTx : indexedBlock.getTransactions()) {
            ChainTransaction chainTx = indexedTx.getChainTransaction();
            chainTx.setIndexedAt(LocalDateTime.now());
            chainTransactionMapper.insert(chainTx);
        }
    }

    public boolean isRealtimeIndexing(String chainId) {
        AtomicBoolean flag = realtimeIndexingFlags.get(chainId);
        return flag != null && flag.get();
    }
}
