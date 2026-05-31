package com.chainetl.modules.indexer.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chainetl.common.exception.BusinessException;
import com.chainetl.common.util.IdGenerator;
import com.chainetl.modules.chain.dto.BlockData;
import com.chainetl.modules.chain.dto.TransactionData;
import com.chainetl.modules.chain.service.ChainAdapterService;
import com.chainetl.modules.indexer.dto.*;
import com.chainetl.modules.indexer.mapper.IndexedBlockMapper;
import com.chainetl.modules.indexer.mapper.IndexedTransactionMapper;
import com.chainetl.modules.indexer.model.IndexedBlock;
import com.chainetl.modules.indexer.model.IndexedTransaction;
import com.github.benmanes.caffeine.cache.Cache;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BlockIndexerService {

    private final IndexedBlockMapper blockMapper;
    private final IndexedTransactionMapper transactionMapper;
    private final ChainAdapterService chainAdapterService;
    private final IndexerMetricsService metricsService;
    private final Cache<String, Object> caffeineCache;

    private final Map<String, String> lastIndexedBlockHashes = new ConcurrentHashMap<>();

    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_PENDING = "PENDING";

    private static final String CACHE_BLOCK_PREFIX = "indexer:block:";
    private static final String CACHE_TX_PREFIX = "indexer:tx:";
    private static final String CACHE_TS_PREFIX = "indexer:ts:";

    @Transactional
    @Retry(name = "indexer", fallbackMethod = "indexBlockFallback")
    @Timed(value = "indexer.block.index.timed", description = "Time taken to index a single block")
    public Mono<IndexedBlockResponse> indexBlock(IndexBlockRequest request) {
        return Mono.fromCallable(() -> {
            long startTime = System.currentTimeMillis();
            String chainId = request.getChainId();
            Long blockNumber = request.getBlockNumber();

            LambdaQueryWrapper<IndexedBlock> existingWrapper = new LambdaQueryWrapper<>();
            existingWrapper.eq(IndexedBlock::getChainId, chainId)
                    .eq(IndexedBlock::getBlockNumber, blockNumber);
            if (blockMapper.selectCount(existingWrapper) > 0) {
                log.warn("Block already indexed: chain={}, number={}", chainId, blockNumber);
                return getBlockByNumber(chainId, blockNumber).block();
            }

            BlockData blockData;
            if (request.getRawBlockData() != null && !request.getRawBlockData().isEmpty()) {
                blockData = parseRawBlockData(chainId, request.getRawBlockData());
            } else {
                long rpcStart = System.currentTimeMillis();
                blockData = chainAdapterService.getBlockByNumber(chainId, blockNumber, true).block();
                long rpcTime = System.currentTimeMillis() - rpcStart;
                log.debug("RPC fetch time for block {}: {}ms", blockNumber, rpcTime);
            }

            if (blockData == null) {
                metricsService.recordBlockFailed(chainId, blockNumber);
                throw new BusinessException(404, "Block not found: " + blockNumber);
            }

            long insertStart = System.currentTimeMillis();

            String blockId = IdGenerator.generateBlockId();
            Instant now = Instant.now();

            IndexedBlock indexedBlock = IndexedBlock.builder()
                    .blockId(blockId)
                    .chainId(chainId)
                    .blockNumber(blockData.getBlockNumber())
                    .blockHash(blockData.getBlockHash())
                    .parentHash(blockData.getParentHash())
                    .timestamp(blockData.getTimestamp())
                    .transactionCount(blockData.getTransactions() != null ? blockData.getTransactions().size() : 0)
                    .rawData(request.getRawBlockData())
                    .indexedAt(now)
                    .build();
            blockMapper.insert(indexedBlock);

            List<IndexedTransactionResponse> txResponses = new ArrayList<>();
            if (blockData.getTransactions() != null) {
                for (TransactionData txData : blockData.getTransactions()) {
                    String txId = IdGenerator.generateTxId();
                    IndexedTransaction indexedTx = IndexedTransaction.builder()
                            .txId(txId)
                            .chainId(chainId)
                            .blockNumber(blockNumber)
                            .txHash(txData.getTxHash())
                            .fromAddress(txData.getFromAddress())
                            .toAddress(txData.getToAddress())
                            .value(txData.getValue())
                            .gasUsed(txData.getGasUsed())
                            .gasPrice(txData.getGasPrice())
                            .status(txData.getStatus() != null ? txData.getStatus() : STATUS_SUCCESS)
                            .inputData(txData.getInputData())
                            .indexedAt(now)
                            .build();
                    transactionMapper.insert(indexedTx);
                    txResponses.add(toTxResponse(indexedTx));
                }
            }

            long insertTime = System.currentTimeMillis() - insertStart;
            long totalTime = System.currentTimeMillis() - startTime;
            int txCount = txResponses.size();

            metricsService.recordBlockIndexed(chainId, blockNumber, txCount, totalTime);

            if (blockData.getRawData() != null) {
                metricsService.recordIndexSize(blockData.getRawData().length());
            }

            lastIndexedBlockHashes.put(chainId, blockData.getBlockHash());
            caffeineCache.put(CACHE_BLOCK_PREFIX + chainId + ":" + blockNumber, indexedBlock);
            caffeineCache.put(CACHE_TS_PREFIX + chainId, now.toEpochMilli());

            log.info("Indexed block: chain={}, number={}, txCount={}, insertTime={}ms, totalTime={}ms",
                    chainId, blockNumber, txCount, insertTime, totalTime);

            return toBlockResponse(indexedBlock, txResponses);
        });
    }

    @Retry(name = "indexer", fallbackMethod = "indexRangeFallback")
    public Mono<String> indexBlockRange(IndexRangeRequest request) {
        return Mono.fromCallable(() -> {
            String chainId = request.getChainId();
            long startBlock = request.getStartBlock();
            long endBlock = request.getEndBlock();

            if (startBlock > endBlock) {
                throw new BusinessException(400, "startBlock must be less than or equal to endBlock");
            }

            String runId = IdGenerator.generateRunId();
            log.info("Starting block range index: runId={}, chain={}, blocks={}-{}",
                    runId, chainId, startBlock, endBlock);

            Flux<Long> blockNumbers = Flux.rangeLong(startBlock, endBlock - startBlock + 1);

            if (Boolean.TRUE.equals(request.getParallel())) {
                blockNumbers.parallel()
                        .runOn(Schedulers.parallel())
                        .flatMap(blockNum -> indexBlock(IndexBlockRequest.builder()
                                .chainId(chainId)
                                .blockNumber(blockNum)
                                .build()))
                        .sequential()
                        .doOnComplete(() -> log.info("Completed parallel index range: runId={}", runId))
                        .subscribe();
            } else {
                blockNumbers.concatMap(blockNum -> indexBlock(IndexBlockRequest.builder()
                                .chainId(chainId)
                                .blockNumber(blockNum)
                                .build()))
                        .doOnComplete(() -> log.info("Completed sequential index range: runId={}", runId))
                        .subscribe();
            }

            return runId;
        });
    }

    @Cacheable(value = "indexerBlocks", key = "#chainId + ':' + #blockNumber", unless = "#result == null")
    public Mono<IndexedBlockResponse> getBlockByNumber(String chainId, Long blockNumber) {
        return Mono.fromCallable(() -> {
            String cacheKey = CACHE_BLOCK_PREFIX + chainId + ":" + blockNumber;
            Object cached = caffeineCache.getIfPresent(cacheKey);
            if (cached != null) {
                log.debug("L1 cache hit for block: chain={}, number={}", chainId, blockNumber);
                IndexedBlock block = (IndexedBlock) cached;
                List<IndexedTransaction> transactions = getTransactionsForBlock(chainId, blockNumber);
                List<IndexedTransactionResponse> txResponses = transactions.stream()
                        .map(this::toTxResponse)
                        .collect(Collectors.toList());
                return toBlockResponse(block, txResponses);
            }

            LambdaQueryWrapper<IndexedBlock> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(IndexedBlock::getChainId, chainId)
                    .eq(IndexedBlock::getBlockNumber, blockNumber);
            IndexedBlock block = blockMapper.selectOne(wrapper);
            if (block == null) {
                throw new BusinessException(404, "Block not found: " + blockNumber);
            }

            caffeineCache.put(cacheKey, block);

            List<IndexedTransaction> transactions = getTransactionsForBlock(chainId, blockNumber);
            List<IndexedTransactionResponse> txResponses = transactions.stream()
                    .map(this::toTxResponse)
                    .collect(Collectors.toList());

            return toBlockResponse(block, txResponses);
        });
    }

    public Mono<IndexedBlockResponse> getBlockByHash(String chainId, String blockHash) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<IndexedBlock> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(IndexedBlock::getChainId, chainId)
                    .eq(IndexedBlock::getBlockHash, blockHash);
            IndexedBlock block = blockMapper.selectOne(wrapper);
            if (block == null) {
                throw new BusinessException(404, "Block not found: " + blockHash);
            }

            List<IndexedTransaction> transactions = getTransactionsForBlock(chainId, block.getBlockNumber());
            List<IndexedTransactionResponse> txResponses = transactions.stream()
                    .map(this::toTxResponse)
                    .collect(Collectors.toList());

            return toBlockResponse(block, txResponses);
        });
    }

    @Cacheable(value = "indexerTxns", key = "#chainId + ':' + #txHash", unless = "#result == null")
    public Mono<IndexedTransactionResponse> getTransactionByHash(String chainId, String txHash) {
        return Mono.fromCallable(() -> {
            String cacheKey = CACHE_TX_PREFIX + chainId + ":" + txHash;
            Object cached = caffeineCache.getIfPresent(cacheKey);
            if (cached != null) {
                log.debug("L1 cache hit for tx: chain={}, hash={}", chainId, txHash);
                return toTxResponse((IndexedTransaction) cached);
            }

            LambdaQueryWrapper<IndexedTransaction> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(IndexedTransaction::getChainId, chainId)
                    .eq(IndexedTransaction::getTxHash, txHash);
            IndexedTransaction tx = transactionMapper.selectOne(wrapper);
            if (tx == null) {
                throw new BusinessException(404, "Transaction not found: " + txHash);
            }

            caffeineCache.put(cacheKey, tx);
            return toTxResponse(tx);
        });
    }

    public Mono<List<IndexedTransactionResponse>> getTransactionsByAddress(
            String chainId, String address, Integer limit, Integer offset) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<IndexedTransaction> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(IndexedTransaction::getChainId, chainId)
                    .and(w -> w.eq(IndexedTransaction::getFromAddress, address)
                            .or().eq(IndexedTransaction::getToAddress, address))
                    .orderByDesc(IndexedTransaction::getBlockNumber)
                    .last("LIMIT " + (limit != null ? limit : 100) +
                            " OFFSET " + (offset != null ? offset : 0));

            List<IndexedTransaction> transactions = transactionMapper.selectList(wrapper);
            return transactions.stream()
                    .map(this::toTxResponse)
                    .collect(Collectors.toList());
        });
    }

    public Mono<List<IndexedBlockResponse>> listBlocks(
            String chainId, Long startBlock, Long endBlock, Integer limit, Integer offset) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<IndexedBlock> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(IndexedBlock::getChainId, chainId);
            if (startBlock != null) {
                wrapper.ge(IndexedBlock::getBlockNumber, startBlock);
            }
            if (endBlock != null) {
                wrapper.le(IndexedBlock::getBlockNumber, endBlock);
            }
            wrapper.orderByDesc(IndexedBlock::getBlockNumber)
                    .last("LIMIT " + (limit != null ? limit : 100) +
                            " OFFSET " + (offset != null ? offset : 0));

            List<IndexedBlock> blocks = blockMapper.selectList(wrapper);
            return blocks.stream()
                    .map(block -> {
                        List<IndexedTransaction> txs = getTransactionsForBlock(chainId, block.getBlockNumber());
                        List<IndexedTransactionResponse> txResponses = txs.stream()
                                .map(this::toTxResponse)
                                .collect(Collectors.toList());
                        return toBlockResponse(block, txResponses);
                    })
                    .collect(Collectors.toList());
        });
    }

    public Mono<IndexerStatusResponse> getIndexerStatus(String chainId) {
        return Mono.fromCallable(() -> {
            long totalBlocks = metricsService.getChainBlockCount(chainId);
            long lastBlockNum = metricsService.getLastIndexedBlockNumber(chainId);
            long lastTs = metricsService.getLastIndexedTimestamp(chainId);
            String lastHash = lastIndexedBlockHashes.get(chainId);

            double avgTime = metricsService.getAverageIndexTimeMs(chainId);
            double p95Time = metricsService.getP95IndexTimeMs(chainId);
            double p99Time = metricsService.getP99IndexTimeMs(chainId);

            long totalIndexed = metricsService.getTotalBlocksIndexed();
            long totalTxs = metricsService.getTotalTransactionsIndexed();
            long totalFailed = metricsService.getTotalFailedBlocks();

            Map<String, Long> blockCounts = new HashMap<>();
            blockCounts.put(chainId, totalBlocks);

            IndexerStatusResponse.IndexerRunStatus runStatus =
                    IndexerStatusResponse.IndexerRunStatus.builder()
                            .activeRuns(0L)
                            .totalRuns(totalIndexed + totalFailed)
                            .successfulRuns(totalIndexed)
                            .failedRuns(totalFailed)
                            .successRate(totalIndexed > 0 ?
                                    (double) totalIndexed / (totalIndexed + totalFailed) * 100 : 0.0)
                            .build();

            return IndexerStatusResponse.builder()
                    .chainId(chainId)
                    .totalBlocksIndexed(totalIndexed)
                    .totalTransactionsIndexed(totalTxs)
                    .totalFailedBlocks(totalFailed)
                    .averageIndexTimeMs(avgTime)
                    .p95IndexTimeMs(p95Time)
                    .p99IndexTimeMs(p99Time)
                    .lastIndexedAt(lastTs > 0 ? Instant.ofEpochMilli(lastTs) : null)
                    .lastIndexedBlockNumber(lastBlockNum)
                    .lastIndexedBlockHash(lastHash)
                    .status(runStatus)
                    .blockIndexCounts(blockCounts)
                    .build();
        });
    }

    public Mono<Map<String, Object>> getGlobalMetrics() {
        return Mono.fromCallable(() -> {
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("totalBlocksIndexed", metricsService.getTotalBlocksIndexed());
            metrics.put("totalTransactionsIndexed", metricsService.getTotalTransactionsIndexed());
            metrics.put("totalFailedBlocks", metricsService.getTotalFailedBlocks());
            metrics.put("averageIndexTimeMs", metricsService.getGlobalAverageIndexTimeMs());
            metrics.put("p95IndexTimeMs", metricsService.getGlobalP95IndexTimeMs());
            metrics.put("p99IndexTimeMs", metricsService.getGlobalP99IndexTimeMs());

            Set<String> chainIds = new HashSet<>(lastIndexedBlockHashes.keySet());
            List<Map<String, Object>> chainStatuses = new ArrayList<>();
            for (String cid : chainIds) {
                Map<String, Object> cs = new LinkedHashMap<>();
                cs.put("chainId", cid);
                cs.put("blocksIndexed", metricsService.getChainBlockCount(cid));
                cs.put("lastBlockNumber", metricsService.getLastIndexedBlockNumber(cid));
                cs.put("lastIndexedAt", Instant.ofEpochMilli(
                        metricsService.getLastIndexedTimestamp(cid)));
                cs.put("averageIndexTimeMs", metricsService.getAverageIndexTimeMs(cid));
                cs.put("p95IndexTimeMs", metricsService.getP95IndexTimeMs(cid));
                chainStatuses.add(cs);
            }
            metrics.put("chains", chainStatuses);

            return metrics;
        });
    }

    @Transactional
    public Mono<Void> deleteBlock(String chainId, Long blockNumber) {
        return Mono.fromRunnable(() -> {
            LambdaQueryWrapper<IndexedBlock> blockWrapper = new LambdaQueryWrapper<>();
            blockWrapper.eq(IndexedBlock::getChainId, chainId)
                    .eq(IndexedBlock::getBlockNumber, blockNumber);
            blockMapper.delete(blockWrapper);

            LambdaQueryWrapper<IndexedTransaction> txWrapper = new LambdaQueryWrapper<>();
            txWrapper.eq(IndexedTransaction::getChainId, chainId)
                    .eq(IndexedTransaction::getBlockNumber, blockNumber);
            transactionMapper.delete(txWrapper);

            caffeineCache.invalidate(CACHE_BLOCK_PREFIX + chainId + ":" + blockNumber);

            log.info("Deleted block index: chain={}, number={}", chainId, blockNumber);
        });
    }

    private BlockData parseRawBlockData(String chainId, String rawData) {
        try {
            JSONObject json = JSON.parseObject(rawData);
            return BlockData.builder()
                    .chainId(chainId)
                    .blockNumber(json.getLong("number"))
                    .blockHash(json.getString("hash"))
                    .parentHash(json.getString("parentHash"))
                    .timestamp(Instant.ofEpochSecond(json.getLongValue("timestamp")))
                    .transactions(new ArrayList<>())
                    .rawData(rawData)
                    .build();
        } catch (Exception e) {
            throw new BusinessException(400, "Failed to parse raw block data: " + e.getMessage());
        }
    }

    private List<IndexedTransaction> getTransactionsForBlock(String chainId, Long blockNumber) {
        LambdaQueryWrapper<IndexedTransaction> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(IndexedTransaction::getChainId, chainId)
                .eq(IndexedTransaction::getBlockNumber, blockNumber);
        return transactionMapper.selectList(wrapper);
    }

    private IndexedBlockResponse toBlockResponse(IndexedBlock block, List<IndexedTransactionResponse> transactions) {
        return IndexedBlockResponse.builder()
                .blockId(block.getBlockId())
                .chainId(block.getChainId())
                .blockNumber(block.getBlockNumber())
                .blockHash(block.getBlockHash())
                .parentHash(block.getParentHash())
                .timestamp(block.getTimestamp())
                .transactionCount(block.getTransactionCount())
                .indexedAt(block.getIndexedAt())
                .transactions(transactions)
                .build();
    }

    private IndexedTransactionResponse toTxResponse(IndexedTransaction tx) {
        return IndexedTransactionResponse.builder()
                .txId(tx.getTxId())
                .chainId(tx.getChainId())
                .blockNumber(tx.getBlockNumber())
                .txHash(tx.getTxHash())
                .fromAddress(tx.getFromAddress())
                .toAddress(tx.getToAddress())
                .value(tx.getValue())
                .gasUsed(tx.getGasUsed())
                .gasPrice(tx.getGasPrice())
                .status(tx.getStatus())
                .inputData(tx.getInputData())
                .indexedAt(tx.getIndexedAt())
                .build();
    }

    private Mono<IndexedBlockResponse> indexBlockFallback(IndexBlockRequest request, Exception e) {
        log.error("Index block fallback triggered: {}", e.getMessage(), e);
        if (request != null) {
            metricsService.recordBlockFailed(request.getChainId(), request.getBlockNumber());
        }
        throw new BusinessException("Failed to index block after retries: " + e.getMessage());
    }

    private Mono<String> indexRangeFallback(IndexRangeRequest request, Exception e) {
        log.error("Index range fallback triggered: {}", e.getMessage(), e);
        throw new BusinessException("Failed to index block range after retries: " + e.getMessage());
    }
}
