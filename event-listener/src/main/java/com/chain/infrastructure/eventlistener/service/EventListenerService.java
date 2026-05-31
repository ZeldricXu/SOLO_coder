package com.chain.infrastructure.eventlistener.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.chain.infrastructure.common.util.IdGenerator;
import com.chain.infrastructure.common.util.JsonUtils;
import com.chain.infrastructure.eventlistener.callback.EventCallback;
import com.chain.infrastructure.eventlistener.callback.HttpEventCallback;
import com.chain.infrastructure.eventlistener.dto.EventLog;
import com.chain.infrastructure.eventlistener.dto.EventSubscriptionRequest;
import com.chain.infrastructure.persistence.entity.ContractEvent;
import com.chain.infrastructure.persistence.mapper.ContractEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventListenerService {

    private final ContractEventMapper contractEventMapper;
    private final HttpEventCallback httpEventCallback;
    private final Map<String, List<EventCallback>> callbacks = new ConcurrentHashMap<>();

    public Mono<String> subscribe(EventSubscriptionRequest request) {
        return Mono.fromCallable(() -> {
            String subscriptionId = IdGenerator.generateId("sub");

            ContractEvent subscription = new ContractEvent();
            subscription.setEventId(subscriptionId);
            subscription.setChainType(request.getChainType());
            subscription.setContractAddress(request.getContractAddress());
            subscription.setEventSignature(request.getEventSignature());
            subscription.setEventName(request.getEventName());
            subscription.setTopics(JsonUtils.toJson(request.getTopics()));
            subscription.setProcessed(false);
            contractEventMapper.insert(subscription);

            log.info("Event subscription created: subscriptionId={}, chain={}, contract={}, event={}",
                    subscriptionId, request.getChainType(), request.getContractAddress(), request.getEventName());

            return subscriptionId;
        });
    }

    public Mono<Void> processEvent(EventLog eventLog) {
        return Mono.fromRunnable(() -> {
            String eventId = IdGenerator.generateId("evt");

            ContractEvent event = new ContractEvent();
            event.setEventId(eventId);
            event.setChainType(eventLog.getChainType());
            event.setBlockNumber(eventLog.getBlockNumber());
            event.setTxHash(eventLog.getTxHash());
            event.setLogIndex(eventLog.getLogIndex());
            event.setContractAddress(eventLog.getContractAddress());
            event.setEventSignature(eventLog.getEventSignature());
            event.setEventName(eventLog.getEventName());
            event.setTopics(JsonUtils.toJson(eventLog.getTopics()));
            event.setData(eventLog.getData());
            event.setDecodedData(eventLog.getDecodedData() != null ? JsonUtils.toJson(eventLog.getDecodedData()) : null);
            event.setProcessed(false);
            contractEventMapper.insert(event);

            triggerCallbacks(eventLog);

            log.info("Event processed: eventId={}, eventName={}, txHash={}",
                    eventId, eventLog.getEventName(), eventLog.getTxHash());
        });
    }

    private void triggerCallbacks(EventLog eventLog) {
        String key = eventLog.getChainType() + ":" + eventLog.getContractAddress() + ":" + eventLog.getEventSignature();
        List<EventCallback> eventCallbacks = callbacks.getOrDefault(key, new ArrayList<>());
        eventCallbacks.forEach(callback -> {
            try {
                callback.onEvent(eventLog);
            } catch (Exception e) {
                log.error("Callback execution failed: callback={}, error={}",
                        callback.getName(), e.getMessage());
            }
        });
    }

    public void registerCallback(String chainType, String contractAddress, String eventSignature, EventCallback callback) {
        String key = chainType + ":" + contractAddress + ":" + eventSignature;
        callbacks.computeIfAbsent(key, k -> new ArrayList<>()).add(callback);
        log.info("Callback registered: key={}, callback={}", key, callback.getName());
    }

    public Mono<Void> markEventProcessed(String eventId) {
        return Mono.fromRunnable(() -> {
            ContractEvent event = contractEventMapper.selectById(eventId);
            if (event != null) {
                event.setProcessed(true);
                event.setProcessedAt(LocalDateTime.now());
                contractEventMapper.updateById(event);
            }
        });
    }

    public Flux<ContractEvent> getUnprocessedEvents(String chainType) {
        return Flux.fromIterable(() -> {
            QueryWrapper<ContractEvent> wrapper = new QueryWrapper<>();
            wrapper.eq("chain_type", chainType)
                    .eq("processed", false)
                    .orderByAsc("block_number");
            return contractEventMapper.selectList(wrapper).iterator();
        });
    }

    public Flux<ContractEvent> getEventsByContract(String chainType, String contractAddress) {
        return Flux.fromIterable(() -> {
            QueryWrapper<ContractEvent> wrapper = new QueryWrapper<>();
            wrapper.eq("chain_type", chainType)
                    .eq("contract_address", contractAddress)
                    .orderByDesc("block_number");
            return contractEventMapper.selectList(wrapper).iterator();
        });
    }

    public Flux<ContractEvent> getEventsByTxHash(String chainType, String txHash) {
        return Flux.fromIterable(() -> {
            QueryWrapper<ContractEvent> wrapper = new QueryWrapper<>();
            wrapper.eq("chain_type", chainType)
                    .eq("tx_hash", txHash)
                    .orderByAsc("log_index");
            return contractEventMapper.selectList(wrapper).iterator();
        });
    }
}
