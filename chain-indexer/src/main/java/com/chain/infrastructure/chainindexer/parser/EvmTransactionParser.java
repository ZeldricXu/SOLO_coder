package com.chain.infrastructure.chainindexer.parser;

import com.chain.infrastructure.chainindexer.dto.BlockData;
import com.chain.infrastructure.chainindexer.dto.TransactionData;
import com.chain.infrastructure.common.util.IdGenerator;
import com.chain.infrastructure.persistence.entity.IndexedTransaction;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class EvmTransactionParser implements TransactionParser {

    @Override
    public Mono<IndexedTransaction> parse(BlockData blockData, TransactionData txData) {
        return Mono.fromCallable(() -> {
            String txId = IdGenerator.generateId("txidx");

            IndexedTransaction indexedTx = new IndexedTransaction();
            indexedTx.setTxId(txId);
            indexedTx.setChainType(blockData.getChainType());
            indexedTx.setBlockNumber(blockData.getBlockNumber());
            indexedTx.setTxHash(txData.getTxHash());
            indexedTx.setTxIndex(txData.getTxIndex());
            indexedTx.setFromAddress(txData.getFromAddress());
            indexedTx.setToAddress(txData.getToAddress());
            indexedTx.setValue(txData.getValue());
            indexedTx.setGasPrice(txData.getGasPrice());
            indexedTx.setGasUsed(txData.getGasUsed());
            indexedTx.setInputData(txData.getInputData());
            indexedTx.setStatus(txData.getStatus());
            indexedTx.setContractAddress(txData.getContractAddress());

            return indexedTx;
        });
    }

    @Override
    public String getChainType() {
        return "EVM";
    }
}
