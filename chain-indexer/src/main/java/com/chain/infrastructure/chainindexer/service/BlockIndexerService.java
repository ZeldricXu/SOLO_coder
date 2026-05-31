package com.chain.infrastructure.chainindexer.service;

import com.chain.infrastructure.chainindexer.dto.BlockData;
import com.chain.infrastructure.chainindexer.factory.ParserFactory;
import com.chain.infrastructure.chainindexer.monitor.IndexerMetrics;
import com.chain.infrastructure.chainindexer.monitor.IndexerStatusEndpoint;
import com.chain.infrastructure.chainindexer.parser.BlockParser;
import com.chain.infrastructure.chainindexer.parser.TransactionParser;
import com.chain.infrastructure.chainindexer.repository.IndexRepository;
import com.chain.infrastructure.chainindexer.strategy.IndexingStrategy;
import com.chain.infrastructure.persistence.entity.IndexedBlock;
import com.chain.infrastructure.persistence.entity.IndexedTransaction;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BlockIndexerService {

    private final ParserFactory parserFactory;
    private final IndexRepository indexRepository;
    private final List<IndexingStrategy> indexingStrategies;
    private final IndexerMetrics metrics;
    private final IndexerStatusEndpoint statusEndpoint;

    public Mono<IndexedBlock> indexBlock(BlockData blockData) {
        Timer.Sample sample = metrics.startTimer("index_block");
        long startTime = System.currentTimeMillis();

        IndexingStrategy strategy = selectStrategy(blockData);

        return strategy.shouldIndex(blockData)
                .flatMap(shouldIndex -> {
                    if (!shouldIndex) {
                        metrics.incrementCounter("blocks_skipped");
                        log.debug("Skipping block: chain={}, number={}",
                                blockData.getChainType(), blockData.getBlockNumber());
                        return Mono.empty();
                    }
                    return doIndexBlock(blockData, startTime, sample);
                })
                .doOnError(e -> {
                    metrics.incrementCounter("blocks_failed");
                    statusEndpoint.setStatus("ERROR");
                    log.error("Block indexing failed: chain={}, number={}, error={}",
                            blockData.getChainType(), blockData.getBlockNumber(), e.getMessage());
                });
    }

    private Mono<IndexedBlock> doIndexBlock(BlockData blockData, long startTime, Timer.Sample sample) {
        BlockParser blockParser = parserFactory.getBlockParser(blockData.getChainType());
        TransactionParser txParser = parserFactory.getTransactionParser(blockData.getChainType());

        return blockParser.parse(blockData)
                .flatMap(indexRepository::saveBlock)
                .flatMap(savedBlock -> indexTransactions(blockData, txParser)
                        .thenReturn(savedBlock)
                )
                .doOnSuccess(block -> {
                    long elapsed = System.currentTimeMillis() - startTime;
                    metrics.recordTimer("index_block", sample);
                    metrics.recordBlockIndexingTime(Duration.ofMillis(elapsed));
                    metrics.incrementBlocksIndexed();
                    if (block.getTxCount() != null) {
                        metrics.incrementTransactionsIndexed(block.getTxCount());
                        metrics.recordTransactionCountPerBlock(block.getTxCount());
                    }
                    if (blockData.getRawData() != null) {
                        metrics.recordBlockSize(blockData.getRawData().length());
                    }
                    metrics.incrementCounter("blocks_success");
                    statusEndpoint.updateLastIndexed(block.getChainType(), block.getBlockNumber());
                    log.info("Block indexed: blockId={}, chain={}, number={}, txCount={}, time={}ms",
                            block.getBlockId(), block.getChainType(), block.getBlockNumber(),
                            block.getTxCount(), elapsed);
                });
    }

    private Mono<Void> indexTransactions(BlockData blockData, TransactionParser txParser) {
        if (blockData.getTransactions() == null || blockData.getTransactions().isEmpty()) {
            return Mono.empty();
        }

        Timer.Sample sample = metrics.startTimer("index_transactions");

        return Flux.fromIterable(blockData.getTransactions())
                .flatMap(tx -> {
                    Timer.Sample txSample = metrics.startTimer("index_transaction_single");
                    return txParser.parse(blockData, tx)
                            .doOnNext(parsed -> metrics.recordTimer("index_transaction_single", txSample));
                })
                .collectList()
                .flatMap(indexRepository::saveTransactions)
                .doOnNext(txs -> metrics.recordTimer("index_transactions", sample))
                .then();
    }

    private IndexingStrategy selectStrategy(BlockData blockData) {
        return indexingStrategies.stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No indexing strategy available"));
    }

    public Mono<IndexedBlock> getBlockByNumber(String chainType, Long blockNumber) {
        Timer.Sample sample = metrics.startTimer("query_block");
        return indexRepository.findBlockByNumber(chainType, blockNumber)
                .doOnNext(block -> metrics.recordTimer("query_block", sample))
                .doOnError(e -> metrics.incrementCounter("queries_failed"));
    }

    public Flux<IndexedTransaction> getTransactionsByBlock(String chainType, Long blockNumber) {
        Timer.Sample sample = metrics.startTimer("query_transactions_by_block");
        return indexRepository.findTransactionsByBlock(chainType, blockNumber)
                .doOnComplete(() -> metrics.recordTimer("query_transactions_by_block", sample));
    }

    public Flux<IndexedTransaction> getTransactionsByAddress(String chainType, String address) {
        Timer.Sample sample = metrics.startTimer("query_transactions_by_address");
        return indexRepository.findTransactionsByAddress(chainType, address)
                .doOnComplete(() -> metrics.recordTimer("query_transactions_by_address", sample));
    }

    public Mono<Long> getLatestBlockNumber(String chainType) {
        return indexRepository.findLatestBlockNumber(chainType);
    }
}
