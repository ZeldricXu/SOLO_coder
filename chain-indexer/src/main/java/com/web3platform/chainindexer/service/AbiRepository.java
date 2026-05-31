package com.web3platform.chainindexer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web3platform.chaininteraction.model.EventLog;
import com.web3platform.chainindexer.model.DecodedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Type;
import org.web3j.crypto.Hash;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class AbiRepository {

    private final Map<String, String> abiStore = new ConcurrentHashMap<>();
    private final Map<String, List<JsonNode>> abiMethodsCache = new ConcurrentHashMap<>();
    private final Map<String, List<JsonNode>> abiEventsCache = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void registerAbi(String contractAddress, String abiJson) {
        String normalizedAddress = contractAddress.toLowerCase();
        abiStore.put(normalizedAddress, abiJson);
        parseAndCacheAbi(normalizedAddress, abiJson);
        log.info("Registered ABI for contract: {}", contractAddress);
    }

    public String getAbi(String contractAddress) {
        return abiStore.get(contractAddress.toLowerCase());
    }

    private void parseAndCacheAbi(String contractAddress, String abiJson) {
        try {
            JsonNode abiNode = objectMapper.readTree(abiJson);
            List<JsonNode> methods = new ArrayList<>();
            List<JsonNode> events = new ArrayList<>();

            for (JsonNode entry : abiNode) {
                String type = entry.has("type") ? entry.get("type").asText() : "";
                if ("function".equals(type)) {
                    methods.add(entry);
                } else if ("event".equals(type)) {
                    events.add(entry);
                }
            }

            abiMethodsCache.put(contractAddress, methods);
            abiEventsCache.put(contractAddress, events);
        } catch (Exception e) {
            log.error("Failed to parse ABI for contract: {}", contractAddress, e);
            throw new RuntimeException("Failed to parse ABI", e);
        }
    }

    public Map<String, String> decodeMethod(String inputData, String abiJson) {
        Map<String, String> result = new HashMap<>();
        if (inputData == null || inputData.length() < 10) {
            return result;
        }

        try {
            JsonNode abiNode = objectMapper.readTree(abiJson);
            String methodSelector = inputData.substring(0, 10);

            for (JsonNode entry : abiNode) {
                if ("function".equals(entry.get("type").asText())) {
                    String signature = buildFunctionSignature(entry);
                    String selector = Hash.sha3String(signature).substring(0, 10);

                    if (methodSelector.equals(selector)) {
                        result.put("methodName", entry.get("name").asText());

                        @SuppressWarnings("rawtypes")
                        List inputTypes = new ArrayList<>();
                        List<String> paramNames = new ArrayList<>();
                        JsonNode inputs = entry.get("inputs");
                        for (JsonNode input : inputs) {
                            paramNames.add(input.get("name").asText());
                            inputTypes.add(TypeReference.makeTypeReference(input.get("type").asText()));
                        }

                        String encodedParams = inputData.substring(10);
                        @SuppressWarnings("unchecked")
                        List<Type> decodedParams = FunctionReturnDecoder.decode(encodedParams, inputTypes);

                        for (int i = 0; i < decodedParams.size(); i++) {
                            String value = decodedParams.get(i).getValue() != null
                                    ? decodedParams.get(i).getValue().toString()
                                    : "null";
                            result.put(paramNames.get(i), value);
                        }
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to decode method input", e);
        }
        return result;
    }

    public DecodedEvent decodeEvent(EventLog eventLog, String abiJson) {
        try {
            JsonNode abiNode = objectMapper.readTree(abiJson);
            String topic0 = eventLog.getTopics().get(0);

            for (JsonNode entry : abiNode) {
                if ("event".equals(entry.get("type").asText())) {
                    String signature = buildEventSignature(entry);
                    String encodedSignature = Hash.sha3String(signature);

                    if (topic0.equals(encodedSignature)) {
                        String eventName = entry.get("name").asText();
                        Map<String, String> params = new HashMap<>();

                        @SuppressWarnings("rawtypes")
                        List indexedTypes = new ArrayList<>();
                        List<String> indexedNames = new ArrayList<>();
                        @SuppressWarnings("rawtypes")
                        List nonIndexedTypes = new ArrayList<>();
                        List<String> nonIndexedNames = new ArrayList<>();

                        JsonNode inputs = entry.get("inputs");
                        for (JsonNode input : inputs) {
                            boolean indexed = input.has("indexed") && input.get("indexed").asBoolean();
                            String name = input.get("name").asText();
                            String type = input.get("type").asText();
                            if (indexed) {
                                indexedNames.add(name);
                                indexedTypes.add(TypeReference.makeTypeReference(type));
                            } else {
                                nonIndexedNames.add(name);
                                nonIndexedTypes.add(TypeReference.makeTypeReference(type));
                            }
                        }

                        for (int i = 0; i < indexedTypes.size() && i < eventLog.getTopics().size() - 1; i++) {
                            @SuppressWarnings("unchecked")
                            TypeReference<Type> typeRef = (TypeReference<Type>) indexedTypes.get(i);
                            List<TypeReference<Type>> typeRefList = Collections.singletonList(typeRef);
                            List<Type> decoded = FunctionReturnDecoder.decode(
                                    eventLog.getTopics().get(i + 1),
                                    typeRefList
                            );
                            if (!decoded.isEmpty()) {
                                params.put(indexedNames.get(i), decoded.get(0).getValue().toString());
                            }
                        }

                        @SuppressWarnings("unchecked")
                        List<Type> decodedNonIndexed = FunctionReturnDecoder.decode(
                                eventLog.getData(),
                                nonIndexedTypes
                        );
                        for (int i = 0; i < decodedNonIndexed.size() && i < nonIndexedNames.size(); i++) {
                            params.put(nonIndexedNames.get(i), decodedNonIndexed.get(i).getValue().toString());
                        }

                        return DecodedEvent.builder()
                                .eventName(eventName)
                                .contractAddress(eventLog.getAddress())
                                .params(params)
                                .build();
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to decode event log", e);
        }
        return null;
    }

    private String buildFunctionSignature(JsonNode functionEntry) {
        String name = functionEntry.get("name").asText();
        JsonNode inputs = functionEntry.get("inputs");
        List<String> types = new ArrayList<>();
        for (JsonNode input : inputs) {
            types.add(input.get("type").asText());
        }
        return name + "(" + String.join(",", types) + ")";
    }

    private String buildEventSignature(JsonNode eventEntry) {
        String name = eventEntry.get("name").asText();
        JsonNode inputs = eventEntry.get("inputs");
        List<String> types = new ArrayList<>();
        for (JsonNode input : inputs) {
            types.add(input.get("type").asText());
        }
        return name + "(" + String.join(",", types) + ")";
    }
}
