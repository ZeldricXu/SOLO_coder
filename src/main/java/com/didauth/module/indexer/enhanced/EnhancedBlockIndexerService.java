package com.didauth.module.indexer.enhanced;

import com.didauth.common.enums.ChainType;
import com.didauth.common.exception.BusinessException;
import com.didauth.core.entity.BlockIndex;
import com.didauth.core.entity.TransactionIndex;
import com.didauth.core.mapper.BlockIndexMapper;
import com.didauth.core.mapper.TransactionIndexMapper;
import com.didauth.module.indexer.dto.BlockParseRequest;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class EnhancedBlockIndexerService {

    private final BlockIndexMapper blockIndexMapper;
    private final TransactionIndexMapper transactionIndexMapper;
    private final IndexerMetricsCollector metricsCollector;

    private final IndexerMetrics metrics = new IndexerMetrics();

    @PostConstruct
    public void init() {
        for (ChainType chainType : ChainType.values()) {
            metricsCollector.registerBlockGauge(chainType.getCode());
        }
        log.info("Enhanced Block Indexer Service initialized with Prometheus metrics");
    }

    @Transactional
    public Mono<String> parseAndIndexBlock(BlockParseRequest request) {
        ChainType chainType = ChainType.fromCode(request.getChainType());

        Timer.Sample totalSample = Timer.start(metricsCollector.getMeterRegistry());
        Timer.Sample parseSample = Timer.start(metricsCollector.getMeterRegistry());

        try {
            BlockIndex existingBlock = blockIndexMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<BlockIndex>()
                            .eq(BlockIndex::getChainType, chainType.getCode())
                            .eq(BlockIndex::getBlockNumber, request.getBlockNumber()));

            if (existingBlock != null) {
                log.warn("Block already indexed: chain={}, number={}", chainType.getCode(), request.getBlockNumber());
                metricsCollector.getSkippedCounter(chainType.getCode()).increment();
                metrics.recordBlockSkipped();
                return Mono.just(existingBlock.getId());
            }

            parseSample.stop(metricsCollector.getBlockParseTimer(chainType.getCode()));

            Timer.Sample dbSample = Timer.start(metricsCollector.getMeterRegistry());

            BlockIndex blockIndex = new BlockIndex();
            blockIndex.setChainType(chainType.getCode());
            blockIndex.setBlockNumber(request.getBlockNumber());
            blockIndex.setBlockHash(request.getBlockHash());
            blockIndex.setParentHash(request.getParentHash());
            blockIndex.setMiner(request.getMiner());
            blockIndex.setTimestamp(request.getTimestamp());
            blockIndex.setTransactionCount(request.getTransactions() != null ? request.getTransactions().size() : 0);
            blockIndex.setGasLimit(request.getGasLimit());
            blockIndex.setGasUsed(request.getGasUsed());
            blockIndex.setExtraData(request.getExtraData());
            blockIndex.setStatus("INDEXED");

            blockIndexMapper.insert(blockIndex);

            int txCount = 0;
            if (request.getTransactions() != null) {
                for (var tx : request.getTransactions()) {
                    Timer.Sample txSample = Timer.start(metricsCollector.getMeterRegistry());
                    try {
                        TransactionIndex existingTx = transactionIndexMapper.selectOne(
                                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TransactionIndex>()
                                        .eq(TransactionIndex::getChainType, chainType.getCode())
                                        .eq(TransactionIndex::getTxHash, tx.getTxHash()));

                        if (existingTx == null) {
                            TransactionIndex txIndex = new TransactionIndex();
                            txIndex.setChainType(chainType.getCode());
                            txIndex.setBlockNumber(request.getBlockNumber());
                            txIndex.setTxHash(tx.getTxHash());
                            txIndex.setTxIndex(tx.getTxIndex());
                            txIndex.setFromAddress(tx.getFromAddress());
                            txIndex.setToAddress(tx.getToAddress());
                            txIndex.setValue(tx.getValue());
                            txIndex.setGasPrice(tx.getGasPrice());
                            txIndex.setGasLimit(tx.getGasLimit());
                            txIndex.setGasUsed(tx.getGasUsed());
                            txIndex.setInputData(tx.getInputData());
                            txIndex.setStatus(tx.getStatus());
                            txIndex.setContractAddress(tx.getContractAddress());
                            txIndex.setTimestamp(request.getTimestamp());

                            transactionIndexMapper.insert(txIndex);
                            txCount++;
                        }
                    } finally {
                        txSample.stop(metricsCollector.getTransactionIndexingTimer(chainType.getCode()));
                    }
                }
            }

            dbSample.stop(metricsCollector.getDbInsertTimer(chainType.getCode()));

            long totalDurationMs = totalSample.stop(metricsCollector.getBlockIndexingTimer(chainType.getCode())) / 1_000_000;

            metricsCollector.getBlockCounter(chainType.getCode()).increment();
            metricsCollector.getTransactionCounter(chainType.getCode()).increment(txCount);
            metricsCollector.setLastBlockNumber(chainType.getCode(), request.getBlockNumber());
            metricsCollector.setLastIndexingDuration(chainType.getCode(), totalDurationMs);

            metrics.recordBlockIndexed(request.getBlockNumber(), request.getTimestamp(), totalDurationMs, txCount);

            log.info("Block indexed [enhanced]: chain={}, number={}, txCount={}, duration={}ms, avgBlockTime={:.2f}ms",
                    chainType.getCode(), request.getBlockNumber(), txCount, totalDurationMs,
                    metrics.getAvgBlockIndexingTimeMs());

            return Mono.just(blockIndex.getId());

        } catch (Exception e) {
            metricsCollector.getErrorCounter(chainType.getCode()).increment();
            metrics.recordError();
            log.error("Failed to index block: chain={}, number={}", chainType.getCode(), request.getBlockNumber(), e);
            return Mono.error(BusinessException.internalError("Block indexing failed: " + e.getMessage()));
        }
    }

    public Mono<BlockIndex> getBlockByNumber(String chainType, Long blockNumber) {
        return Mono.fromCallable(() -> {
            ChainType.fromCode(chainType);
            BlockIndex block = blockIndexMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<BlockIndex>()
                            .eq(BlockIndex::getChainType, chainType)
                            .eq(BlockIndex::getBlockNumber, blockNumber));
            if (block == null) {
                throw BusinessException.notFound("Block not found");
            }
            return block;
        });
    }

    public Mono<Map<String, Object>> getIndexerStatus(String chainType) {
        return Mono.fromCallable(() -> {
            Map<String, Object> status = new HashMap<>();
            status.put("chain", chainType);
            status.put("totalBlocksIndexed", metrics.getTotalBlocksIndexed().get());
            status.put("totalTransactionsIndexed", metrics.getTotalTransactionsIndexed().get());
            status.put("totalBlocksSkipped", metrics.getTotalBlocksSkipped().get());
            status.put("totalErrors", metrics.getTotalErrors().get());
            status.put("lastBlockNumber", metrics.getLastBlockNumber());
            status.put("lastBlockTimestamp", metrics.getLastBlockTimestamp());
            status.put("lastIndexingDurationMs", metrics.getLastIndexingDurationMs());
            status.put("avgBlockIndexingTimeMs", metrics.getAvgBlockIndexingTimeMs());
            status.put("avgTransactionIndexingTimeMs", metrics.getAvgTransactionIndexingTimeMs());
            status.put("indexingRateBlocksPerSecond", metrics.getIndexingRateTps());
            status.put("indexingRateTransactionsPerSecond", metrics.getTransactionRateTps());
            return status;
        });
    }

    public Mono<Map<String, Object>> getAllChainsStatus() {
        return Mono.fromCallable(() -> {
            Map<String, Object> allStatus = new HashMap<>();
            for (ChainType chainType : ChainType.values()) {
                allStatus.put(chainType.getCode(), Map.of(
                        "lastBlockNumber", metricsCollector.getLastBlockNumbers()
                                .getOrDefault(chainType.getCode(), new java.util.concurrent.atomic.AtomicLong(0)).get(),
                        "lastDurationMs", metricsCollector.getLastIndexingDurations()
                                .getOrDefault(chainType.getCode(), new java.util.concurrent.atomic.AtomicLong(0)).get()
                ));
            }
            allStatus.put("global", Map.of(
                    "totalBlocksIndexed", metrics.getTotalBlocksIndexed().get(),
                    "totalTransactionsIndexed", metrics.getTotalTransactionsIndexed().get(),
                    "totalErrors", metrics.getTotalErrors().get()
            ));
            return allStatus;
        });
    }

    public IndexerMetrics getMetrics() {
        return metrics;
    }
}
