package com.didauth.module.event.service;

import com.didauth.common.enums.ChainType;
import com.didauth.common.exception.BusinessException;
import com.didauth.core.entity.ContractEvent;
import com.didauth.core.entity.ContractEventLog;
import com.didauth.core.mapper.ContractEventLogMapper;
import com.didauth.core.mapper.ContractEventMapper;
import com.didauth.module.event.dto.RegisterEventRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.security.MessageDigest;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContractEventService {

    private final ContractEventMapper eventMapper;
    private final ContractEventLogMapper eventLogMapper;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;
    private final WebClient.Builder webClientBuilder;

    public Mono<String> registerEventListener(RegisterEventRequest request) {
        return Mono.fromCallable(() -> {
            ChainType chainType = ChainType.fromCode(request.getChainType());
            String eventId = "event_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

            ContractEvent event = new ContractEvent();
            event.setEventId(eventId);
            event.setChainType(chainType.getCode());
            event.setContractAddress(request.getContractAddress().toLowerCase());
            event.setEventName(request.getEventName());
            event.setTopic0(request.getTopic0() != null ? request.getTopic0().toLowerCase() : generateTopicHash(request.getEventName()));
            event.setTopic1(request.getTopic1() != null ? request.getTopic1().toLowerCase() : null);
            event.setTopic2(request.getTopic2() != null ? request.getTopic2().toLowerCase() : null);
            event.setTopic3(request.getTopic3() != null ? request.getTopic3().toLowerCase() : null);
            event.setFilterParams(request.getFilterParams() != null ? request.getFilterParams().toString() : null);
            event.setCallbackUrl(request.getCallbackUrl());
            event.setCallbackType(request.getCallbackType());
            event.setIsActive(true);
            event.setUserId(request.getUserId());

            eventMapper.insert(event);

            meterRegistry.counter("contract.event.register.count", "chain", chainType.getCode()).increment();

            log.info("Contract event listener registered: eventId={}, contract={}, event={}",
                    eventId, request.getContractAddress(), request.getEventName());

            return eventId;
        });
    }

    private String generateTopicHash(String eventName) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(eventName.getBytes());
            return "0x" + bytesToHex(hash);
        } catch (Exception e) {
            return "0x" + UUID.randomUUID().toString().replace("-", "");
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    public Mono<Void> emitEvent(String chainType, String contractAddress, String txHash, Long blockNumber,
                                Integer logIndex, String eventData, String decodedData) {
        return Mono.fromCallable(() -> {
            List<ContractEvent> listeners = findMatchingListeners(chainType, contractAddress, decodedData);

            for (ContractEvent listener : listeners) {
                try {
                    processEvent(listener, chainType, contractAddress, txHash, blockNumber, logIndex, eventData, decodedData);
                } catch (Exception e) {
                    log.error("Failed to process event for listener: {}", listener.getEventId(), e);
                }
            }

            return null;
        });
    }

    private List<ContractEvent> findMatchingListeners(String chainType, String contractAddress, String decodedData) {
        var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ContractEvent>();
        wrapper.eq(ContractEvent::getChainType, chainType.toUpperCase());
        wrapper.eq(ContractEvent::getContractAddress, contractAddress.toLowerCase());
        wrapper.eq(ContractEvent::getIsActive, true);
        return eventMapper.selectList(wrapper);
    }

    private void processEvent(ContractEvent listener, String chainType, String contractAddress, String txHash,
                              Long blockNumber, Integer logIndex, String eventData, String decodedData) {
        String logId = "log_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        ContractEventLog eventLog = new ContractEventLog();
        eventLog.setEventId(listener.getEventId());
        eventLog.setChainType(chainType.toUpperCase());
        eventLog.setBlockNumber(blockNumber);
        eventLog.setTxHash(txHash);
        eventLog.setLogIndex(logIndex);
        eventLog.setContractAddress(contractAddress.toLowerCase());
        eventLog.setEventData(eventData);
        eventLog.setDecodedData(decodedData);
        eventLog.setCallbackStatus("PENDING");
        eventLog.setTimestamp(System.currentTimeMillis());

        eventLogMapper.insert(eventLog);

        invokeCallback(listener, eventLog)
                .subscribe(
                        success -> {
                            eventLog.setCallbackStatus("SUCCESS");
                            eventLog.setCallbackResponse(success);
                            eventLogMapper.updateById(eventLog);
                            meterRegistry.counter("contract.event.callback.success").increment();
                        },
                        error -> {
                            eventLog.setCallbackStatus("FAILED");
                            eventLog.setCallbackResponse(error.getMessage());
                            eventLogMapper.updateById(eventLog);
                            meterRegistry.counter("contract.event.callback.failed").increment();
                        }
                );

        meterRegistry.counter("contract.event.emitted.count", "chain", chainType, "event", listener.getEventName()).increment();
    }

    private Mono<String> invokeCallback(ContractEvent listener, ContractEventLog eventLog) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("eventId", listener.getEventId());
        payload.put("eventName", listener.getEventName());
        payload.put("chainType", eventLog.getChainType());
        payload.put("blockNumber", eventLog.getBlockNumber());
        payload.put("txHash", eventLog.getTxHash());
        payload.put("logIndex", eventLog.getLogIndex());
        payload.put("contractAddress", eventLog.getContractAddress());
        payload.put("decodedData", eventLog.getDecodedData());
        payload.put("timestamp", eventLog.getTimestamp());

        return webClientBuilder.build()
                .post()
                .uri(listener.getCallbackUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(java.time.Duration.ofSeconds(10));
    }

    public Mono<List<ContractEvent>> listEventListeners(String chainType, String contractAddress, String userId) {
        return Mono.fromCallable(() -> {
            var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ContractEvent>();
            if (chainType != null) wrapper.eq(ContractEvent::getChainType, chainType.toUpperCase());
            if (contractAddress != null) wrapper.eq(ContractEvent::getContractAddress, contractAddress.toLowerCase());
            if (userId != null) wrapper.eq(ContractEvent::getUserId, userId);
            wrapper.orderByDesc(ContractEvent::getCreatedAt);
            return eventMapper.selectList(wrapper);
        });
    }

    public Mono<ContractEvent> getEventListener(String eventId) {
        return Mono.fromCallable(() -> {
            ContractEvent event = eventMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ContractEvent>()
                            .eq(ContractEvent::getEventId, eventId));
            if (event == null) {
                throw BusinessException.notFound("Event listener not found: " + eventId);
            }
            return event;
        });
    }

    public Mono<Void> toggleEventListener(String eventId, boolean active) {
        return Mono.fromCallable(() -> {
            ContractEvent event = eventMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ContractEvent>()
                            .eq(ContractEvent::getEventId, eventId));
            if (event == null) {
                throw BusinessException.notFound("Event listener not found: " + eventId);
            }
            event.setIsActive(active);
            eventMapper.updateById(event);
            return null;
        });
    }

    public Mono<List<ContractEventLog>> getEventLogs(String eventId, Integer limit) {
        return Mono.fromCallable(() -> {
            var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ContractEventLog>();
            wrapper.eq(ContractEventLog::getEventId, eventId);
            wrapper.orderByDesc(ContractEventLog::getBlockNumber);
            wrapper.last("LIMIT " + (limit != null ? limit : 100));
            return eventLogMapper.selectList(wrapper);
        });
    }

    @Scheduled(fixedRate = 60000)
    public void simulateEventPolling() {
        List<ContractEvent> activeListeners = eventMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ContractEvent>()
                        .eq(ContractEvent::getIsActive, true));

        Random random = new Random();
        for (ContractEvent listener : activeListeners) {
            if (random.nextDouble() < 0.1) {
                try {
                    Map<String, Object> decodedData = new HashMap<>();
                    decodedData.put("from", "0x" + UUID.randomUUID().toString().replace("-", "").substring(0, 40));
                    decodedData.put("to", "0x" + UUID.randomUUID().toString().replace("-", "").substring(0, 40));
                    decodedData.put("value", random.nextInt(1000000));
                    decodedData.put("timestamp", System.currentTimeMillis());

                    emitEvent(
                            listener.getChainType(),
                            listener.getContractAddress(),
                            "0x" + UUID.randomUUID().toString().replace("-", ""),
                            System.currentTimeMillis() / 1000,
                            random.nextInt(100),
                            "0x" + UUID.randomUUID().toString().replace("-", ""),
                            objectMapper.writeValueAsString(decodedData)
                    ).subscribe();
                } catch (Exception e) {
                    log.warn("Failed to simulate event", e);
                }
            }
        }
    }
}
