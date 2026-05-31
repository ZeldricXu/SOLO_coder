package com.chain.infrastructure.chainindexer.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.chain.infrastructure.persistence.entity.IndexedBlock;
import com.chain.infrastructure.persistence.entity.IndexedTransaction;
import com.chain.infrastructure.persistence.mapper.IndexedBlockMapper;
import com.chain.infrastructure.persistence.mapper.IndexedTransactionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class IndexRepositoryImpl implements IndexRepository {

    private final IndexedBlockMapper blockMapper;
    private final IndexedTransactionMapper transactionMapper;

    @Override
    public Mono<IndexedBlock> saveBlock(IndexedBlock block) {
        return Mono.fromCallable(() -> {
            blockMapper.insert(block);
            return block;
        });
    }

    @Override
    public Mono<List<IndexedTransaction>> saveTransactions(List<IndexedTransaction> transactions) {
        return Flux.fromIterable(transactions)
                .flatMap(tx -> Mono.fromCallable(() -> {
                    transactionMapper.insert(tx);
                    return tx;
                }))
                .collectList();
    }

    @Override
    public Mono<IndexedBlock> findBlockByNumber(String chainType, Long blockNumber) {
        return Mono.fromCallable(() -> {
            QueryWrapper<IndexedBlock> wrapper = new QueryWrapper<>();
            wrapper.eq("chain_type", chainType)
                    .eq("block_number", blockNumber);
            return blockMapper.selectOne(wrapper);
        });
    }

    @Override
    public Flux<IndexedTransaction> findTransactionsByBlock(String chainType, Long blockNumber) {
        return Flux.fromIterable(() -> {
            QueryWrapper<IndexedTransaction> wrapper = new QueryWrapper<>();
            wrapper.eq("chain_type", chainType)
                    .eq("block_number", blockNumber)
                    .orderByAsc("tx_index");
            return transactionMapper.selectList(wrapper).iterator();
        });
    }

    @Override
    public Flux<IndexedTransaction> findTransactionsByAddress(String chainType, String address) {
        return Flux.fromIterable(() -> {
            QueryWrapper<IndexedTransaction> wrapper = new QueryWrapper<>();
            wrapper.eq("chain_type", chainType)
                    .and(w -> w.eq("from_address", address).or().eq("to_address", address))
                    .orderByDesc("block_number");
            return transactionMapper.selectList(wrapper).iterator();
        });
    }

    @Override
    public Mono<Long> findLatestBlockNumber(String chainType) {
        return Mono.fromCallable(() -> {
            QueryWrapper<IndexedBlock> wrapper = new QueryWrapper<>();
            wrapper.eq("chain_type", chainType)
                    .orderByDesc("block_number")
                    .last("LIMIT 1");
            IndexedBlock block = blockMapper.selectOne(wrapper);
            return block != null ? block.getBlockNumber() : 0L;
        });
    }
}
