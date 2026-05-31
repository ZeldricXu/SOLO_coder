package com.didauth.module.indexer.service;

import com.didauth.common.enums.ChainType;
import com.didauth.common.exception.BusinessException;
import com.didauth.core.entity.BlockIndex;
import com.didauth.core.entity.TransactionIndex;
import com.didauth.core.mapper.BlockIndexMapper;
import com.didauth.core.mapper.TransactionIndexMapper;
import com.didauth.module.indexer.dto.BlockParseRequest;
import com.didauth.module.indexer.dto.TransactionParseRequest;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BlockIndexerService {

    private final BlockIndexMapper blockIndexMapper;
    private final TransactionIndexMapper transactionIndexMapper;
    private final MeterRegistry meterRegistry;

    @Transactional
    public Mono<String> parseAndIndexBlock(BlockParseRequest request) {
        return Mono.fromCallable(() -> {
            ChainType chainType = ChainType.fromCode(request.getChainType());

            BlockIndex existingBlock = blockIndexMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<BlockIndex>()
                            .eq(BlockIndex::getChainType, chainType.getCode())
                            .eq(BlockIndex::getBlockNumber, request.getBlockNumber()));

            if (existingBlock != null) {
                log.warn("Block already indexed: chain={}, number={}", chainType.getCode(), request.getBlockNumber());
                return existingBlock.getId();
            }

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

            if (request.getTransactions() != null) {
                for (TransactionParseRequest tx : request.getTransactions()) {
                    indexTransaction(chainType.getCode(), request.getBlockNumber(), request.getTimestamp(), tx);
                }
            }

            meterRegistry.counter("block.index.count", "chain", chainType.getCode()).increment();
            meterRegistry.gauge("block.index.latest", "chain", chainType.getCode()).set(request.getBlockNumber());

            log.info("Block indexed successfully: chain={}, number={}, txCount={}",
                    chainType.getCode(), request.getBlockNumber(), blockIndex.getTransactionCount());

            return blockIndex.getId();
        });
    }

    private void indexTransaction(String chainType, Long blockNumber, Long blockTimestamp, TransactionParseRequest tx) {
        TransactionIndex existingTx = transactionIndexMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TransactionIndex>()
                        .eq(TransactionIndex::getChainType, chainType)
                        .eq(TransactionIndex::getTxHash, tx.getTxHash()));

        if (existingTx != null) {
            return;
        }

        TransactionIndex txIndex = new TransactionIndex();
        txIndex.setChainType(chainType);
        txIndex.setBlockNumber(blockNumber);
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
        txIndex.setTimestamp(blockTimestamp);

        transactionIndexMapper.insert(txIndex);
    }

    public Mono<BlockIndex> getBlockByNumber(String chainType, Long blockNumber) {
        return Mono.fromCallable(() -> {
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

    public Mono<TransactionIndex> getTransactionByHash(String chainType, String txHash) {
        return Mono.fromCallable(() -> {
            TransactionIndex tx = transactionIndexMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TransactionIndex>()
                            .eq(TransactionIndex::getChainType, chainType)
                            .eq(TransactionIndex::getTxHash, txHash));
            if (tx == null) {
                throw BusinessException.notFound("Transaction not found");
            }
            return tx;
        });
    }

    public Flux<TransactionIndex> getTransactionsByAddress(String chainType, String address, Integer limit, Integer offset) {
        return Mono.fromCallable(() -> {
            var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TransactionIndex>();
            wrapper.eq(TransactionIndex::getChainType, chainType);
            wrapper.and(w -> w.eq(TransactionIndex::getFromAddress, address).or().eq(TransactionIndex::getToAddress, address));
            wrapper.orderByDesc(TransactionIndex::getBlockNumber);
            wrapper.last("LIMIT " + limit + " OFFSET " + offset);
            return transactionIndexMapper.selectList(wrapper);
        }).flatMapMany(Flux::fromIterable);
    }

    public Mono<List<BlockIndex>> getLatestBlocks(String chainType, Integer limit) {
        return Mono.fromCallable(() -> {
            var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<BlockIndex>();
            wrapper.eq(BlockIndex::getChainType, chainType);
            wrapper.orderByDesc(BlockIndex::getBlockNumber);
            wrapper.last("LIMIT " + limit);
            return blockIndexMapper.selectList(wrapper);
        });
    }
}
