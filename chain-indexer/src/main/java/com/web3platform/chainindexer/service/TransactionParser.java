package com.web3platform.chainindexer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web3platform.chaininteraction.model.EventLog;
import com.web3platform.chaininteraction.model.UnifiedTransaction;
import com.web3platform.chainindexer.model.DecodedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Hash;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionParser {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AbiRepository abiRepository;

    private static final String TRANSFER_EVENT_SIGNATURE = "Transfer(address,address,uint256)";
    private static final String TRANSFER_EVENT_HASH = Hash.sha3String(TRANSFER_EVENT_SIGNATURE);

    public UnifiedTransaction parseRawTx(String rawJson) {
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

    public DecodedEvent decodeEventLog(EventLog eventLog, Map<String, String> abiMap) {
        if (eventLog == null || eventLog.getTopics() == null || eventLog.getTopics().isEmpty()) {
            return null;
        }

        String contractAddress = eventLog.getAddress();
        String abi = abiMap != null ? abiMap.get(contractAddress.toLowerCase()) : null;

        if (abi == null) {
            abi = abiRepository.getAbi(contractAddress);
        }

        if (abi != null) {
            DecodedEvent decoded = abiRepository.decodeEvent(eventLog, abi);
            if (decoded != null) {
                return decoded;
            }
        }

        return tryDecodeKnownEvents(eventLog);
    }

    public List<DecodedEvent> extractTransferEvents(UnifiedTransaction tx) {
        List<DecodedEvent> result = new ArrayList<>();
        List<EventLog> logs = extractLogsFromTransaction(tx);

        for (int i = 0; i < logs.size(); i++) {
            EventLog eventLog = logs.get(i);
            if (eventLog.getTopics() != null && !eventLog.getTopics().isEmpty()) {
                String topic0 = eventLog.getTopics().get(0);
                if (TRANSFER_EVENT_HASH.equals(topic0)) {
                    DecodedEvent event = decodeTransferEvent(eventLog, i);
                    if (event != null) {
                        event.setTxHash(tx.getTxHash());
                        result.add(event);
                    }
                }
            }
        }
        return result;
    }

    private DecodedEvent tryDecodeKnownEvents(EventLog eventLog) {
        if (eventLog.getTopics() != null && !eventLog.getTopics().isEmpty()) {
            String topic0 = eventLog.getTopics().get(0);
            if (TRANSFER_EVENT_HASH.equals(topic0)) {
                return decodeTransferEvent(eventLog, 0);
            }
        }
        return null;
    }

    private DecodedEvent decodeTransferEvent(EventLog eventLog, int logIndex) {
        try {
            Map<String, String> params = new HashMap<>();

            if (eventLog.getTopics().size() >= 3) {
                String from = "0x" + eventLog.getTopics().get(1).substring(26);
                String to = "0x" + eventLog.getTopics().get(2).substring(26);
                params.put("from", from);
                params.put("to", to);
            }

            if (eventLog.getData() != null && eventLog.getData().length() > 2) {
                String value = new java.math.BigInteger(eventLog.getData().substring(2), 16).toString();
                params.put("value", value);
            }

            return DecodedEvent.builder()
                    .eventName("Transfer")
                    .contractAddress(eventLog.getAddress())
                    .params(params)
                    .logIndex(logIndex)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to decode Transfer event", e);
            return null;
        }
    }

    private List<EventLog> extractLogsFromTransaction(UnifiedTransaction tx) {
        List<EventLog> logs = new ArrayList<>();
        try {
            if (tx.getInputData() != null && tx.getInputData().contains("logs")) {
                JsonNode txNode = objectMapper.readTree(tx.getInputData());
                JsonNode logsNode = txNode.get("logs");
                if (logsNode != null && logsNode.isArray()) {
                    for (JsonNode logNode : logsNode) {
                        List<String> topics = new ArrayList<>();
                        JsonNode topicsNode = logNode.get("topics");
                        if (topicsNode != null && topicsNode.isArray()) {
                            for (JsonNode topicNode : topicsNode) {
                                topics.add(topicNode.asText());
                            }
                        }
                        logs.add(EventLog.builder()
                                .address(logNode.get("address").asText())
                                .topics(topics)
                                .data(logNode.get("data").asText())
                                .build());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not extract logs from transaction input", e);
        }
        return logs;
    }
}
