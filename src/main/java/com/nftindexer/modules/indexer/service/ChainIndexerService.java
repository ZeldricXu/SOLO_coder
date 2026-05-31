package com.nftindexer.modules.indexer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nftindexer.common.JsonUtils;
import com.nftindexer.common.OptimisticRetry;
import com.nftindexer.common.TraceContext;
import com.nftindexer.entity.ChainBlock;
import com.nftindexer.entity.ChainIndexerState;
import com.nftindexer.entity.ChainTransaction;
import com.nftindexer.entity.NftMetadata;
import com.nftindexer.event.DomainEvent;
import com.nftindexer.exception.BusinessException;
import com.nftindexer.mapper.ChainBlockMapper;
import com.nftindexer.mapper.ChainIndexerStateMapper;
import com.nftindexer.mapper.ChainTransactionMapper;
import com.nftindexer.mapper.NftMetadataMapper;
import com.nftindexer.modules.indexer.dto.BlockIndexRequest;
import com.nftindexer.modules.indexer.dto.NftMetadataIndexRequest;
import com.nftindexer.modules.indexer.dto.TransactionIndexRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChainIndexerService {

    private final ChainBlockMapper blockMapper;
    private final ChainTransactionMapper transactionMapper;
    private final NftMetadataMapper nftMetadataMapper;
    private final ChainIndexerStateMapper indexerStateMapper;
    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final Sinks.Many<DomainEvent> eventSink;

    @Value("${nftindexer.indexer.finalization-confirmations:15}")
    private int finalizationConfirmations;

    @Value("${nftindexer.indexer.batch-size:100}")
    private int batchSize;

    @Transactional
    @OptimisticRetry(maxAttempts = 3)
    public Mono<ChainBlock> indexBlock(BlockIndexRequest request) {
        return TraceContext.getTraceId()
                .flatMap(traceId -> Mono.fromCallable(() -> {
                    String chainId = request.getChainId();
                    Integer blockNumber = request.getBlockNumber();

                    LambdaQueryWrapper<ChainBlock> existingWrapper = new LambdaQueryWrapper<>();
                    existingWrapper.eq(ChainBlock::getChainId, chainId);
                    existingWrapper.eq(ChainBlock::getBlockNumber, blockNumber);
                    if (blockMapper.selectCount(existingWrapper) > 0) {
                        throw BusinessException.conflict("区块已索引: " + blockNumber);
                    }

                    String blockId = "blk-" + UUID.randomUUID().toString().substring(0, 8);
                    ChainBlock block = new ChainBlock();
                    block.setBlockId(blockId);
                    block.setChainId(chainId);
                    block.setBlockNumber(blockNumber);
                    block.setBlockHash(request.getBlockHash());
                    block.setParentHash(request.getParentHash());
                    block.setMiner(request.getMiner());
                    block.setDifficulty(request.getDifficulty());
                    block.setTotalDifficulty(request.getTotalDifficulty());
                    block.setGasLimit(request.getGasLimit());
                    block.setGasUsed(request.getGasUsed());
                    block.setBlockTime(request.getBlockTime() != null ? request.getBlockTime() : LocalDateTime.now());
                    block.setTransactionCount(request.getTransactionCount() != null ?
                            request.getTransactionCount() :
                            (request.getTransactions() != null ? request.getTransactions().size() : 0));
                    block.setLogCount(request.getLogCount());
                    block.setStatus("indexed");
                    block.setIndexedAt(LocalDateTime.now());

                    blockMapper.insert(block);

                    int transactionIndexed = 0;
                    if (request.getTransactions() != null && !request.getTransactions().isEmpty()) {
                        for (TransactionIndexRequest txRequest : request.getTransactions()) {
                            try {
                                txRequest.setBlockHash(request.getBlockHash());
                                txRequest.setBlockNumber(blockNumber);
                                txRequest.setBlockTime(block.getBlockTime());
                                indexTransactionInternal(chainId, txRequest);
                                transactionIndexed++;
                            } catch (Exception e) {
                                log.error("Failed to index transaction {} in block {}",
                                        txRequest.getTxHash(), blockNumber, e);
                            }
                        }
                    }

                    updateIndexerState(chainId, blockNumber, transactionIndexed,
                            request.getLogCount() != null ? request.getLogCount() : 0);

                    emitEvent("block.indexed", blockId, "chain_block",
                            Map.of("blockNumber", blockNumber,
                                    "transactionCount", transactionIndexed), traceId);

                    log.info("Indexed block {} on chain {} with {} transactions",
                            blockNumber, chainId, transactionIndexed);

                    return block;
                }));
    }

    @Transactional
    @OptimisticRetry(maxAttempts = 3)
    public Mono<ChainTransaction> indexTransaction(String chainId, TransactionIndexRequest request) {
        return TraceContext.getTraceId()
                .flatMap(traceId -> Mono.fromCallable(() -> {
                    ChainTransaction tx = indexTransactionInternal(chainId, request);
                    emitEvent("transaction.indexed", tx.getTxIndexId(), "chain_transaction", tx, traceId);
                    return tx;
                }));
    }

    private ChainTransaction indexTransactionInternal(String chainId, TransactionIndexRequest request) {
        LambdaQueryWrapper<ChainTransaction> existingWrapper = new LambdaQueryWrapper<>();
        existingWrapper.eq(ChainTransaction::getChainId, chainId);
        existingWrapper.eq(ChainTransaction::getTxHash, request.getTxHash());
        if (transactionMapper.selectCount(existingWrapper) > 0) {
            throw BusinessException.conflict("交易已索引: " + request.getTxHash());
        }

        String txIndexId = "tx-" + UUID.randomUUID().toString().substring(0, 8);
        ChainTransaction tx = new ChainTransaction();
        tx.setTxIndexId(txIndexId);
        tx.setChainId(chainId);
        tx.setTxHash(request.getTxHash());
        tx.setBlockNumber(request.getBlockNumber());
        tx.setBlockHash(request.getBlockHash());
        tx.setTransactionIndex(request.getTransactionIndex());
        tx.setFromAddress(request.getFromAddress());
        tx.setToAddress(request.getToAddress());
        tx.setContractAddress(request.getContractAddress());
        tx.setValue(request.getValue());
        tx.setGasPrice(request.getGasPrice());
        tx.setGasLimit(request.getGasLimit());
        tx.setGasUsed(request.getGasUsed());
        tx.setNonce(request.getNonce());
        tx.setMethodName(request.getMethodName());
        tx.setMethodSignature(request.getMethodSignature());
        tx.setInputData(request.getInputData());
        tx.setRawInput(request.getRawInput());
        tx.setStatus(request.getStatus() != null ? request.getStatus() : "success");
        tx.setErrorReason(request.getErrorReason());
        tx.setBlockTime(request.getBlockTime() != null ? request.getBlockTime() : LocalDateTime.now());
        tx.setIndexedAt(LocalDateTime.now());
        tx.setMetadata(request.getMetadata());

        transactionMapper.insert(tx);

        log.debug("Indexed transaction {} on chain {}", request.getTxHash(), chainId);
        return tx;
    }

    @Transactional
    @OptimisticRetry(maxAttempts = 3)
    public Mono<NftMetadata> indexNftMetadata(NftMetadataIndexRequest request) {
        return TraceContext.getTraceId()
                .flatMap(traceId -> Mono.fromCallable(() -> {
                    String chainId = request.getChainId();
                    String contractAddress = request.getContractAddress();
                    BigInteger tokenId = request.getTokenId();

                    LambdaQueryWrapper<NftMetadata> existingWrapper = new LambdaQueryWrapper<>();
                    existingWrapper.eq(NftMetadata::getChainId, chainId);
                    existingWrapper.eq(NftMetadata::getContractAddress, contractAddress);
                    existingWrapper.eq(NftMetadata::getTokenId, tokenId);
                    NftMetadata existing = nftMetadataMapper.selectOne(existingWrapper);

                    String metadataId;
                    NftMetadata metadata;
                    boolean isUpdate = false;

                    if (existing != null) {
                        metadataId = existing.getMetadataId();
                        metadata = existing;
                        isUpdate = true;
                    } else {
                        metadataId = "nft-" + UUID.randomUUID().toString().substring(0, 8);
                        metadata = new NftMetadata();
                        metadata.setMetadataId(metadataId);
                        metadata.setChainId(chainId);
                        metadata.setContractAddress(contractAddress);
                        metadata.setTokenId(tokenId);
                        metadata.setStatus("indexed");
                    }

                    if (request.getTokenUri() != null) metadata.setTokenUri(request.getTokenUri());
                    if (request.getName() != null) metadata.setName(request.getName());
                    if (request.getDescription() != null) metadata.setDescription(request.getDescription());
                    if (request.getImage() != null) metadata.setImage(request.getImage());
                    if (request.getAnimationUrl() != null) metadata.setAnimationUrl(request.getAnimationUrl());
                    if (request.getExternalUrl() != null) metadata.setExternalUrl(request.getExternalUrl());
                    if (request.getAttributes() != null) metadata.setAttributes(request.getAttributes());
                    if (request.getProperties() != null) metadata.setProperties(request.getProperties());
                    if (request.getRawMetadata() != null) metadata.setRawMetadata(request.getRawMetadata());
                    if (request.getMetadataHash() != null) {
                        metadata.setMetadataHash(request.getMetadataHash());
                    } else if (request.getRawMetadata() != null) {
                        metadata.setMetadataHash(calculateHash(request.getRawMetadata()));
                    }
                    if (request.getStandard() != null) metadata.setStandard(request.getStandard());
                    if (request.getOwner() != null) metadata.setOwner(request.getOwner());
                    if (request.getCreator() != null) metadata.setCreator(request.getCreator());
                    if (request.getMinter() != null) metadata.setMinter(request.getMinter());
                    if (request.getSupply() != null) metadata.setSupply(request.getSupply());
                    if (request.getMintedAt() != null) metadata.setMintedAt(request.getMintedAt());
                    if (request.getLastUpdatedAt() != null) metadata.setLastUpdatedAt(request.getLastUpdatedAt());
                    if (request.getErrorDetail() != null) metadata.setErrorDetail(request.getErrorDetail());
                    metadata.setIndexedAt(LocalDateTime.now());
                    if (request.getMetadata() != null) {
                        if (metadata.getMetadata() != null) {
                            Map<String, Object> merged = new HashMap<>(metadata.getMetadata());
                            merged.putAll(request.getMetadata());
                            metadata.setMetadata(merged);
                        } else {
                            metadata.setMetadata(request.getMetadata());
                        }
                    }

                    if (isUpdate) {
                        nftMetadataMapper.updateById(metadata);
                        emitEvent("nft.updated", metadataId, "nft_metadata", metadata, traceId);
                        log.info("Updated NFT metadata {} for {}:{} on {}",
                                metadataId, contractAddress, tokenId, chainId);
                    } else {
                        nftMetadataMapper.insert(metadata);
                        emitEvent("nft.indexed", metadataId, "nft_metadata", metadata, traceId);
                        log.info("Indexed NFT metadata {} for {}:{} on {}",
                                metadataId, contractAddress, tokenId, chainId);
                    }

                    cacheNftMetadata(metadata);
                    return metadata;
                }));
    }

    @Cacheable(value = "nftMetadata", key = "#chainId + '_' + #contractAddress + '_' + #tokenId",
            unless = "#result == null")
    public Mono<NftMetadata> getNftMetadata(String chainId, String contractAddress, BigInteger tokenId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<NftMetadata> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(NftMetadata::getChainId, chainId);
            wrapper.eq(NftMetadata::getContractAddress, contractAddress);
            wrapper.eq(NftMetadata::getTokenId, tokenId);
            NftMetadata metadata = nftMetadataMapper.selectOne(wrapper);

            if (metadata == null) {
                throw BusinessException.notFound("NFT元数据不存在: " + contractAddress + ":" + tokenId);
            }
            return metadata;
        });
    }

    @Cacheable(value = "nftMetadata", key = "#metadataId", unless = "#result == null")
    public Mono<NftMetadata> getNftMetadataById(String metadataId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<NftMetadata> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(NftMetadata::getMetadataId, metadataId);
            NftMetadata metadata = nftMetadataMapper.selectOne(wrapper);

            if (metadata == null) {
                throw BusinessException.notFound("NFT元数据不存在: " + metadataId);
            }
            return metadata;
        });
    }

    public Mono<Page<NftMetadata>> searchNftMetadata(String chainId, String contractAddress,
                                                     String owner, String name,
                                                     int pageNum, int pageSize) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<NftMetadata> wrapper = new LambdaQueryWrapper<>();
            if (chainId != null && !chainId.isEmpty()) {
                wrapper.eq(NftMetadata::getChainId, chainId);
            }
            if (contractAddress != null && !contractAddress.isEmpty()) {
                wrapper.eq(NftMetadata::getContractAddress, contractAddress);
            }
            if (owner != null && !owner.isEmpty()) {
                wrapper.eq(NftMetadata::getOwner, owner);
            }
            if (name != null && !name.isEmpty()) {
                wrapper.like(NftMetadata::getName, name);
            }
            wrapper.orderByDesc(NftMetadata::getLastUpdatedAt);
            return nftMetadataMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        });
    }

    public Mono<ChainBlock> getBlock(String chainId, Integer blockNumber) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<ChainBlock> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ChainBlock::getChainId, chainId);
            wrapper.eq(ChainBlock::getBlockNumber, blockNumber);
            ChainBlock block = blockMapper.selectOne(wrapper);

            if (block == null) {
                throw BusinessException.notFound("区块不存在: " + blockNumber);
            }
            return block;
        });
    }

    public Mono<ChainBlock> getBlockByHash(String chainId, String blockHash) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<ChainBlock> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ChainBlock::getChainId, chainId);
            wrapper.eq(ChainBlock::getBlockHash, blockHash);
            ChainBlock block = blockMapper.selectOne(wrapper);

            if (block == null) {
                throw BusinessException.notFound("区块不存在: " + blockHash);
            }
            return block;
        });
    }

    public Mono<Page<ChainBlock>> listBlocks(String chainId, Integer startBlock,
                                             Integer endBlock, int pageNum, int pageSize) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<ChainBlock> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ChainBlock::getChainId, chainId);
            if (startBlock != null) {
                wrapper.ge(ChainBlock::getBlockNumber, startBlock);
            }
            if (endBlock != null) {
                wrapper.le(ChainBlock::getBlockNumber, endBlock);
            }
            wrapper.orderByDesc(ChainBlock::getBlockNumber);
            return blockMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        });
    }

    public Mono<ChainTransaction> getTransaction(String chainId, String txHash) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<ChainTransaction> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ChainTransaction::getChainId, chainId);
            wrapper.eq(ChainTransaction::getTxHash, txHash);
            ChainTransaction tx = transactionMapper.selectOne(wrapper);

            if (tx == null) {
                throw BusinessException.notFound("交易不存在: " + txHash);
            }
            return tx;
        });
    }

    public Mono<Page<ChainTransaction>> listTransactions(String chainId, String fromAddress,
                                                          String toAddress, String contractAddress,
                                                          Integer blockNumber, int pageNum, int pageSize) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<ChainTransaction> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ChainTransaction::getChainId, chainId);
            if (fromAddress != null && !fromAddress.isEmpty()) {
                wrapper.eq(ChainTransaction::getFromAddress, fromAddress);
            }
            if (toAddress != null && !toAddress.isEmpty()) {
                wrapper.eq(ChainTransaction::getToAddress, toAddress);
            }
            if (contractAddress != null && !contractAddress.isEmpty()) {
                wrapper.eq(ChainTransaction::getContractAddress, contractAddress);
            }
            if (blockNumber != null) {
                wrapper.eq(ChainTransaction::getBlockNumber, blockNumber);
            }
            wrapper.orderByDesc(ChainTransaction::getBlockNumber);
            return transactionMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        });
    }

    public Mono<ChainIndexerState> getIndexerState(String chainId, String indexerName) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<ChainIndexerState> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ChainIndexerState::getChainId, chainId);
            wrapper.eq(ChainIndexerState::getIndexerName, indexerName);
            ChainIndexerState state = indexerStateMapper.selectOne(wrapper);

            if (state == null) {
                throw BusinessException.notFound("索引器状态不存在: " + chainId + ":" + indexerName);
            }
            return state;
        });
    }

    public Mono<List<ChainIndexerState>> listIndexerStates(String chainId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<ChainIndexerState> wrapper = new LambdaQueryWrapper<>();
            if (chainId != null && !chainId.isEmpty()) {
                wrapper.eq(ChainIndexerState::getChainId, chainId);
            }
            wrapper.orderByDesc(ChainIndexerState::getLastIndexedAt);
            return indexerStateMapper.selectList(wrapper);
        });
    }

    @Scheduled(fixedRateString = "${nftindexer.indexer.finalize-interval-ms:60000}")
    public void finalizeBlocks() {
        log.debug("Starting block finalization...");
        try {
            LambdaQueryWrapper<ChainIndexerState> stateWrapper = new LambdaQueryWrapper<>();
            List<ChainIndexerState> states = indexerStateMapper.selectList(stateWrapper);

            for (ChainIndexerState state : states) {
                if (state.getLastIndexedBlock() == null) continue;

                int finalizedBlock = state.getLastIndexedBlock() - finalizationConfirmations;
                if (finalizedBlock <= 0) continue;

                if (state.getLastFinalizedBlock() == null || finalizedBlock > state.getLastFinalizedBlock()) {
                    state.setLastFinalizedBlock(finalizedBlock);
                    state.setLastFinalizedAt(LocalDateTime.now());
                    indexerStateMapper.updateById(state);

                    LambdaQueryWrapper<ChainBlock> blockWrapper = new LambdaQueryWrapper<>();
                    blockWrapper.eq(ChainBlock::getChainId, state.getChainId());
                    blockWrapper.eq(ChainBlock::getStatus, "indexed");
                    blockWrapper.le(ChainBlock::getBlockNumber, finalizedBlock);
                    blockWrapper.last("LIMIT " + batchSize);

                    List<ChainBlock> blocksToFinalize = blockMapper.selectList(blockWrapper);
                    for (ChainBlock block : blocksToFinalize) {
                        block.setStatus("finalized");
                        blockMapper.updateById(block);
                    }

                    log.info("Finalized {} blocks up to {} on chain {}",
                            blocksToFinalize.size(), finalizedBlock, state.getChainId());
                }
            }
        } catch (Exception e) {
            log.error("Block finalization failed", e);
        }
    }

    public Mono<Map<String, Object>> getIndexerStats(String chainId) {
        return Mono.fromCallable(() -> {
            Map<String, Object> stats = new HashMap<>();

            LambdaQueryWrapper<ChainIndexerState> stateWrapper = new LambdaQueryWrapper<>();
            stateWrapper.eq(ChainIndexerState::getChainId, chainId);
            stateWrapper.last("LIMIT 1");
            ChainIndexerState state = indexerStateMapper.selectOne(stateWrapper);

            if (state != null) {
                stats.put("chainId", chainId);
                stats.put("lastIndexedBlock", state.getLastIndexedBlock());
                stats.put("lastFinalizedBlock", state.getLastFinalizedBlock());
                stats.put("totalBlocksIndexed", state.getTotalBlocksIndexed());
                stats.put("totalTransactionsIndexed", state.getTotalTransactionsIndexed());
                stats.put("totalLogsIndexed", state.getTotalLogsIndexed());
                stats.put("status", state.getStatus());
                stats.put("lastIndexedAt", state.getLastIndexedAt());
            }

            LambdaQueryWrapper<ChainBlock> blockWrapper = new LambdaQueryWrapper<>();
            blockWrapper.eq(ChainBlock::getChainId, chainId);
            Long totalBlocks = blockMapper.selectCount(blockWrapper);
            stats.put("totalBlocksInDb", totalBlocks);

            LambdaQueryWrapper<NftMetadata> nftWrapper = new LambdaQueryWrapper<>();
            nftWrapper.eq(NftMetadata::getChainId, chainId);
            Long totalNfts = nftMetadataMapper.selectCount(nftWrapper);
            stats.put("totalNftsIndexed", totalNfts);

            return stats;
        });
    }

    private void updateIndexerState(String chainId, int blockNumber,
                                    int transactions, int logs) {
        LambdaQueryWrapper<ChainIndexerState> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChainIndexerState::getChainId, chainId);
        wrapper.eq(ChainIndexerState::getIndexerName, "default");
        ChainIndexerState state = indexerStateMapper.selectOne(wrapper);

        if (state == null) {
            String stateId = "idx-" + UUID.randomUUID().toString().substring(0, 8);
            state = new ChainIndexerState();
            state.setStateId(stateId);
            state.setChainId(chainId);
            state.setIndexerName("default");
            state.setLastIndexedBlock(blockNumber);
            state.setLastFinalizedBlock(Math.max(0, blockNumber - finalizationConfirmations));
            state.setStatus("running");
            state.setLastIndexedAt(LocalDateTime.now());
            state.setLastFinalizedAt(LocalDateTime.now());
            state.setTotalBlocksIndexed(1L);
            state.setTotalTransactionsIndexed((long) transactions);
            state.setTotalLogsIndexed((long) logs);
            indexerStateMapper.insert(state);
        } else {
            if (blockNumber > state.getLastIndexedBlock()) {
                state.setLastIndexedBlock(blockNumber);
                state.setLastIndexedAt(LocalDateTime.now());
            }
            state.setTotalBlocksIndexed((state.getTotalBlocksIndexed() != null ?
                    state.getTotalBlocksIndexed() : 0) + 1);
            state.setTotalTransactionsIndexed((state.getTotalTransactionsIndexed() != null ?
                    state.getTotalTransactionsIndexed() : 0) + transactions);
            state.setTotalLogsIndexed((state.getTotalLogsIndexed() != null ?
                    state.getTotalLogsIndexed() : 0) + logs);
            indexerStateMapper.updateById(state);
        }

        try {
            String cacheKey = "indexer:state:" + chainId;
            redisTemplate.opsForValue().set(cacheKey, state).block();
        } catch (Exception e) {
            log.warn("Failed to cache indexer state", e);
        }
    }

    private String calculateHash(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder("0x");
            for (byte b : hash) {
                hexString.append(String.format("%02x", b));
            }
            return hexString.toString();
        } catch (Exception e) {
            return "0x" + UUID.randomUUID().toString().replace("-", "");
        }
    }

    private void cacheNftMetadata(NftMetadata metadata) {
        try {
            String cacheKey = "nft:metadata:" + metadata.getChainId() + ":" +
                    metadata.getContractAddress() + ":" + metadata.getTokenId();
            redisTemplate.opsForValue().set(cacheKey, metadata).block();
        } catch (Exception e) {
            log.warn("Failed to cache NFT metadata", e);
        }
    }

    private void emitEvent(String eventType, String aggregateId, String aggregateType,
                           Object payload, String traceId) {
        DomainEvent event = new DomainEvent();
        event.setEventId("evt-" + UUID.randomUUID().toString().substring(0, 8));
        event.setEventType(eventType);
        event.setAggregateId(aggregateId);
        event.setAggregateType(aggregateType);
        event.setPayload(Map.of("data", payload));
        event.setTimestamp(LocalDateTime.now());
        event.setTraceId(traceId);
        eventSink.tryEmitNext(event);
    }
}
