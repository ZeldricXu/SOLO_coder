package com.web3platform.chainindexer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web3platform.chaininteraction.model.UnifiedBlock;
import com.web3platform.chaininteraction.model.UnifiedTransaction;
import com.web3platform.chainindexer.model.IndexedBlock;
import com.web3platform.chainindexer.model.IndexedTransaction;
import com.web3platform.persistence.model.entity.ChainBlock;
import com.web3platform.persistence.model.entity.ChainTransaction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class BlockParser {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AbiRepository abiRepository;

    public UnifiedBlock parseRawBlock(String rawJson) {
        try {
            JsonNode blockNode = objectMapper.readTree(rawJson);

            List<UnifiedTransaction> transactions = new ArrayList<>();
            JsonNode txsNode = blockNode.get("transactions");
            if (txsNode != null && txsNode.isArray()) {
                for (JsonNode txNode : txsNode) {
                    transactions.add(parseRawTx(txNode.toString()));
                }
            }

            return UnifiedBlock.builder()
                    .chainId(blockNode.has("chainId") ? blockNode.get("chainId").asText() : "1")
                    .blockNumber(Long.decode(blockNode.get("number").asText()))
                    .blockHash(blockNode.get("hash").asText())
                    .parentHash(blockNode.get("parentHash").asText())
                    .timestamp(Long.decode(blockNode.get("timestamp").asText()))
                    .transactions(transactions)
                    .build();
        } catch (Exception e) {
            log.error("Failed to parse raw block JSON", e);
            throw new RuntimeException("Failed to parse raw block", e);
        }
    }

    public List<IndexedTransaction> extractTransactions(UnifiedBlock block) {
        List<IndexedTransaction> result = new ArrayList<>();
        if (block.getTransactions() == null) {
            return result;
        }

        for (UnifiedTransaction tx : block.getTransactions()) {
            ChainTransaction chainTx = buildChainTransaction(tx);
            Map<String, String> decoded = decodeTransactionInput(tx.getInputData(), null);

            IndexedTransaction indexedTx = IndexedTransaction.builder()
                    .chainTransaction(chainTx)
                    .decodedInput(tx.getInputData())
                    .methodName(decoded.get("methodName"))
                    .params(decoded)
                    .build();

            result.add(indexedTx);
        }
        return result;
    }

    public Map<String, String> decodeTransactionInput(String inputData, Map<String, String> abiMap) {
        Map<String, String> result = new HashMap<>();
        if (inputData == null || inputData.length() < 10) {
            return result;
        }

        if (abiMap != null && !abiMap.isEmpty()) {
            for (Map.Entry<String, String> entry : abiMap.entrySet()) {
                Map<String, String> decoded = abiRepository.decodeMethod(inputData, entry.getValue());
                if (!decoded.isEmpty()) {
                    result.putAll(decoded);
                    result.put("contractAddress", entry.getKey());
                    break;
                }
            }
        }
        return result;
    }

    public IndexedBlock buildIndexedBlock(UnifiedBlock unifiedBlock) {
        ChainBlock chainBlock = new ChainBlock();
        chainBlock.setChainId(unifiedBlock.getChainId());
        chainBlock.setBlockNumber(unifiedBlock.getBlockNumber());
        chainBlock.setBlockHash(unifiedBlock.getBlockHash());
        chainBlock.setParentHash(unifiedBlock.getParentHash());
        chainBlock.setTimestamp(unifiedBlock.getTimestamp());
        chainBlock.setTxCount(unifiedBlock.getTransactions() != null ? unifiedBlock.getTransactions().size() : 0);
        chainBlock.setIndexedAt(LocalDateTime.now());

        List<IndexedTransaction> transactions = extractTransactions(unifiedBlock);

        return IndexedBlock.builder()
                .chainBlock(chainBlock)
                .transactions(transactions)
                .build();
    }

    private UnifiedTransaction parseRawTx(String rawJson) {
        try {
            JsonNode txNode = objectMapper.readTree(rawJson);
            return UnifiedTransaction.builder()
                    .txHash(txNode.get("hash").asText())
                    .blockNumber(txNode.has("blockNumber") ? Long.decode(txNode.get("blockNumber").asText()) : 0)
                    .fromAddr(txNode.get("from").asText())
                    .toAddr(txNode.has("to") ? txNode.get("to").asText() : null)
                    .value(new java.math.BigInteger(txNode.get("value").asText().substring(2), 16))
                    .gasUsed(txNode.has("gas") ? Long.decode(txNode.get("gas").asText()) : 0)
                    .status(1)
                    .inputData(txNode.has("input") ? txNode.get("input").asText() : "0x")
                    .build();
        } catch (Exception e) {
            log.error("Failed to parse raw transaction JSON", e);
            throw new RuntimeException("Failed to parse raw transaction", e);
        }
    }

    private ChainTransaction buildChainTransaction(UnifiedTransaction tx) {
        ChainTransaction chainTx = new ChainTransaction();
        chainTx.setChainId(tx.getChainId());
        chainTx.setBlockNumber(tx.getBlockNumber());
        chainTx.setTxHash(tx.getTxHash());
        chainTx.setFromAddress(tx.getFromAddr());
        chainTx.setToAddress(tx.getToAddr());
        chainTx.setValue(tx.getValue() != null ? new BigDecimal(tx.getValue()) : BigDecimal.ZERO);
        chainTx.setGasUsed(tx.getGasUsed());
        chainTx.setStatus(tx.getStatus());
        chainTx.setIndexedAt(LocalDateTime.now());
        return chainTx;
    }
}
