package com.solocoder.platform.indexer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.solocoder.platform.persistence.entity.BlockDataEntity;
import com.solocoder.platform.persistence.entity.TransactionIndexEntity;
import com.solocoder.platform.persistence.mapper.BlockDataMapper;
import com.solocoder.platform.persistence.mapper.TransactionIndexMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataIndexerService {

    private final BlockDataMapper blockDataMapper;
    private final TransactionIndexMapper transactionIndexMapper;

    @Transactional(rollbackFor = Exception.class)
    public BlockDataEntity indexBlock(String chainId, Long blockNumber, Map<String, Object> blockData) {
        BlockDataEntity entity = new BlockDataEntity();
        entity.setChainId(chainId);
        entity.setBlockNumber(blockNumber);
        entity.setBlockHash((String) blockData.get("hash"));
        entity.setParentHash((String) blockData.get("parentHash"));
        entity.setTimestamp(System.currentTimeMillis());
        entity.setMiner((String) blockData.get("miner"));
        entity.setGasUsed(((Number) blockData.getOrDefault("gasUsed", 0L)).longValue());
        entity.setGasLimit(((Number) blockData.getOrDefault("gasLimit", 0L)).longValue());
        entity.setTransactionCount((Integer) blockData.getOrDefault("transactionCount", 0));
        entity.setIndexStatus("INDEXING");
        entity.setIndexedAt(LocalDateTime.now());
        blockDataMapper.insert(entity);
        return entity;
    }

    @Transactional(rollbackFor = Exception.class)
    public TransactionIndexEntity indexTransaction(String chainId, String txHash, Map<String, Object> txData) {
        TransactionIndexEntity entity = new TransactionIndexEntity();
        entity.setChainId(chainId);
        entity.setTxHash(txHash);
        entity.setBlockNumber(((Number) txData.get("blockNumber")).longValue());
        entity.setBlockHash((String) txData.get("blockHash"));
        entity.setTransactionIndex((Integer) txData.get("transactionIndex"));
        entity.setFromAddress((String) txData.get("from"));
        entity.setToAddress((String) txData.get("to"));
        entity.setValue(new BigDecimal(txData.getOrDefault("value", "0").toString()));
        entity.setGasPrice(new BigDecimal(txData.getOrDefault("gasPrice", "0").toString()));
        entity.setGasLimit(((Number) txData.getOrDefault("gas", 0L)).longValue());
        entity.setStatus((String) txData.getOrDefault("status", "SUCCESS"));
        entity.setTimestamp(System.currentTimeMillis());
        transactionIndexMapper.insert(entity);
        return entity;
    }

    public List<TransactionIndexEntity> queryTransactions(String chainId, String address, Integer limit) {
        LambdaQueryWrapper<TransactionIndexEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(w -> w.eq(TransactionIndexEntity::getFromAddress, address)
                        .or()
                        .eq(TransactionIndexEntity::getToAddress, address))
                .eq(TransactionIndexEntity::getChainId, chainId)
                .orderByDesc(TransactionIndexEntity::getBlockNumber)
                .last("LIMIT " + limit);
        return transactionIndexMapper.selectList(wrapper);
    }

    public BlockDataEntity getLatestBlock(String chainId) {
        LambdaQueryWrapper<BlockDataEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BlockDataEntity::getChainId, chainId)
                .orderByDesc(BlockDataEntity::getBlockNumber)
                .last("LIMIT 1");
        return blockDataMapper.selectOne(wrapper);
    }
}
