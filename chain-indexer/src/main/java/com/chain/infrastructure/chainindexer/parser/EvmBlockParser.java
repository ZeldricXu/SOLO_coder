package com.chain.infrastructure.chainindexer.parser;

import com.chain.infrastructure.chainindexer.dto.BlockData;
import com.chain.infrastructure.chainindexer.dto.TransactionData;
import com.chain.infrastructure.common.util.IdGenerator;
import com.chain.infrastructure.common.util.JsonUtils;
import com.chain.infrastructure.persistence.entity.IndexedBlock;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Component
public class EvmBlockParser implements BlockParser {

    @Override
    public Mono<IndexedBlock> parse(BlockData blockData) {
        return Mono.fromCallable(() -> {
            String blockId = IdGenerator.generateId("blk");

            IndexedBlock indexedBlock = new IndexedBlock();
            indexedBlock.setBlockId(blockId);
            indexedBlock.setChainType(blockData.getChainType());
            indexedBlock.setChainId(blockData.getChainId());
            indexedBlock.setBlockNumber(blockData.getBlockNumber());
            indexedBlock.setBlockHash(blockData.getBlockHash());
            indexedBlock.setParentHash(blockData.getParentHash());
            indexedBlock.setTimestamp(blockData.getTimestamp());
            indexedBlock.setMiner(blockData.getMiner());
            indexedBlock.setDifficulty(blockData.getDifficulty());
            indexedBlock.setGasUsed(blockData.getGasUsed());
            indexedBlock.setGasLimit(blockData.getGasLimit());
            indexedBlock.setTxCount(blockData.getTransactions() != null ? blockData.getTransactions().size() : 0);
            indexedBlock.setRawData(JsonUtils.toJson(blockData));
            indexedBlock.setIndexedAt(LocalDateTime.now());

            return indexedBlock;
        });
    }

    @Override
    public String getChainType() {
        return "EVM";
    }
}
