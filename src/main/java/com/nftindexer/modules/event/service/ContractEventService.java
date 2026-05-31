package com.nftindexer.modules.event.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nftindexer.common.JsonUtils;
import com.nftindexer.common.OptimisticRetry;
import com.nftindexer.common.TraceContext;
import com.nftindexer.entity.ContractEventListener;
import com.nftindexer.entity.ContractEventLog;
import com.nftindexer.entity.RunInstance;
import com.nftindexer.event.DomainEvent;
import com.nftindexer.exception.BusinessException;
import com.nftindexer.mapper.ContractEventListenerMapper;
import com.nftindexer.mapper.ContractEventLogMapper;
import com.nftindexer.mapper.RunInstanceMapper;
import com.nftindexer.modules.event.dto.EventListenerCreateRequest;
import com.nftindexer.modules.event.dto.EventProcessRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContractEventService {

    private final ContractEventListenerMapper listenerMapper;
    private final ContractEventLogMapper eventLogMapper;
    private final RunInstanceMapper runInstanceMapper;
    private final Sinks.Many<DomainEvent> eventSink;
    private final WebClient.Builder webClientBuilder;

    @Value("${nftindexer.event.callback-timeout-seconds:30}")
    private int callbackTimeoutSeconds;

    @Value("${nftindexer.event.max-callback-attempts:3}")
    private int maxCallbackAttempts;

    @Value("${nftindexer.event.retry-interval-minutes:5}")
    private int retryIntervalMinutes;

    @Transactional
    @OptimisticRetry(maxAttempts = 3)
    public Mono<ContractEventListener> createEventListener(EventListenerCreateRequest request) {
        return TraceContext.getTraceId()
                .flatMap(traceId -> Mono.fromCallable(() -> {
                    LambdaQueryWrapper<ContractEventListener> existingWrapper = new LambdaQueryWrapper<>();
                    existingWrapper.eq(ContractEventListener::getChainId, request.getChainId());
                    existingWrapper.eq(ContractEventListener::getContractAddress, request.getContractAddress());
                    existingWrapper.eq(ContractEventListener::getEventName, request.getEventName());
                    existingWrapper.eq(ContractEventListener::getStatus, "active");
                    if (listenerMapper.selectCount(existingWrapper) > 0) {
                        throw BusinessException.conflict("该事件监听器已存在");
                    }

                    String listenerId = "evl-" + UUID.randomUUID().toString().substring(0, 8);
                    String eventSignature = request.getEventSignature() != null ?
                            request.getEventSignature() :
                            generateEventSignature(request.getEventName(), request.getAbi());

                    ContractEventListener listener = new ContractEventListener();
                    listener.setListenerId(listenerId);
                    listener.setChainId(request.getChainId());
                    listener.setContractAddress(request.getContractAddress());
                    listener.setEventName(request.getEventName());
                    listener.setEventSignature(eventSignature);
                    listener.setAbi(request.getAbi());
                    listener.setCallbackUrl(request.getCallbackUrl());
                    listener.setCallbackType(request.getCallbackType() != null ? request.getCallbackType() : "http");
                    listener.setFilterTopics(JsonUtils.toJson(request.getFilterTopics()));
                    listener.setFromBlock(request.getFromBlock() != null ? request.getFromBlock() : 0);
                    listener.setToBlock(request.getToBlock());
                    listener.setStatus("active");
                    listener.setLastProcessedBlock(request.getFromBlock() != null ? request.getFromBlock() - 1 : -1);
                    listener.setConfig(request.getConfig());
                    listener.setCreatedBy(request.getCreatedBy());

                    listenerMapper.insert(listener);

                    String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
                    RunInstance runInstance = new RunInstance();
                    runInstance.setRunId(runId);
                    runInstance.setEntityId(listenerId);
                    runInstance.setPhase("active");
                    runInstance.setProgress(BigDecimal.ZERO);
                    runInstance.setStartedAt(LocalDateTime.now());
                    runInstanceMapper.insert(runInstance);

                    emitEvent("listener.created", listenerId, "event_listener", listener, traceId);
                    log.info("Created event listener: {} for contract {} event {}",
                            listenerId, request.getContractAddress(), request.getEventName());

                    return listener;
                }));
    }

    public Mono<ContractEventListener> getEventListener(String listenerId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<ContractEventListener> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ContractEventListener::getListenerId, listenerId);
            ContractEventListener listener = listenerMapper.selectOne(wrapper);

            if (listener == null) {
                throw BusinessException.notFound("事件监听器不存在: " + listenerId);
            }
            return listener;
        });
    }

    public Mono<Page<ContractEventListener>> listEventListeners(String chainId, String contractAddress,
                                                                String eventName, String status,
                                                                int pageNum, int pageSize) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<ContractEventListener> wrapper = new LambdaQueryWrapper<>();
            if (chainId != null && !chainId.isEmpty()) {
                wrapper.eq(ContractEventListener::getChainId, chainId);
            }
            if (contractAddress != null && !contractAddress.isEmpty()) {
                wrapper.eq(ContractEventListener::getContractAddress, contractAddress);
            }
            if (eventName != null && !eventName.isEmpty()) {
                wrapper.eq(ContractEventListener::getEventName, eventName);
            }
            if (status != null && !status.isEmpty()) {
                wrapper.eq(ContractEventListener::getStatus, status);
            }
            wrapper.orderByDesc(ContractEventListener::getCreatedAt);
            return listenerMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        });
    }

    @Transactional
    @OptimisticRetry(maxAttempts = 3)
    public Mono<ContractEventListener> updateEventListenerStatus(String listenerId, String status, String updatedBy) {
        return TraceContext.getTraceId()
                .flatMap(traceId -> Mono.fromCallable(() -> {
                    LambdaQueryWrapper<ContractEventListener> wrapper = new LambdaQueryWrapper<>();
                    wrapper.eq(ContractEventListener::getListenerId, listenerId);
                    ContractEventListener listener = listenerMapper.selectOne(wrapper);

                    if (listener == null) {
                        throw BusinessException.notFound("事件监听器不存在: " + listenerId);
                    }

                    listener.setStatus(status);
                    listener.setUpdatedBy(updatedBy);
                    listenerMapper.updateById(listener);

                    emitEvent("listener.status_updated", listenerId, "event_listener",
                            Map.of("status", status), traceId);
                    log.info("Updated event listener {} status to {}", listenerId, status);

                    return listener;
                }));
    }

    @Transactional
    @OptimisticRetry(maxAttempts = 3)
    public Mono<ContractEventLog> processEvent(EventProcessRequest request) {
        return TraceContext.getTraceId()
                .flatMap(traceId -> Mono.fromCallable(() -> {
                    LambdaQueryWrapper<ContractEventListener> listenerWrapper = new LambdaQueryWrapper<>();
                    listenerWrapper.eq(ContractEventListener::getListenerId, request.getListenerId());
                    ContractEventListener listener = listenerMapper.selectOne(listenerWrapper);

                    if (listener == null) {
                        throw BusinessException.notFound("事件监听器不存在: " + request.getListenerId());
                    }

                    if (!"active".equals(listener.getStatus())) {
                        throw BusinessException.conflict("事件监听器不活跃: " + listener.getStatus());
                    }

                    String logId = "evt-" + UUID.randomUUID().toString().substring(0, 8);
                    ContractEventLog eventLog = new ContractEventLog();
                    eventLog.setLogId(logId);
                    eventLog.setListenerId(request.getListenerId());
                    eventLog.setChainId(listener.getChainId());
                    eventLog.setContractAddress(request.getContractAddress());
                    eventLog.setTransactionHash(request.getTransactionHash());
                    eventLog.setLogIndex(request.getLogIndex());
                    eventLog.setBlockNumber(request.getBlockNumber());
                    eventLog.setBlockHash(request.getBlockHash());
                    eventLog.setBlockTime(request.getBlockTime() != null ? request.getBlockTime() : LocalDateTime.now());
                    eventLog.setEventName(request.getEventName() != null ? request.getEventName() : listener.getEventName());
                    eventLog.setEventSignature(request.getEventSignature() != null ? request.getEventSignature() : listener.getEventSignature());
                    eventLog.setTopics(request.getTopics());
                    eventLog.setDecodedData(request.getDecodedData());
                    eventLog.setRawData(request.getRawData());
                    eventLog.setStatus("pending");
                    eventLog.setCallbackAttempts(0);

                    eventLogMapper.insert(eventLog);

                    Mono<ContractEventLog> callbackResult = executeCallback(eventLog, listener)
                            .doOnError(e -> {
                                log.error("Callback execution failed for log {}: {}", logId, e.getMessage());
                            });

                    callbackResult.subscribe();

                    updateListenerProgress(listener, request.getBlockNumber());
                    emitEvent("event.processed", logId, "event_log", eventLog, traceId);

                    log.info("Processed event {} from block {} for listener {}",
                            logId, request.getBlockNumber(), request.getListenerId());

                    return eventLog;
                }));
    }

    private Mono<ContractEventLog> executeCallback(ContractEventLog eventLog, ContractEventListener listener) {
        return Mono.defer(() -> {
            String callbackUrl = listener.getCallbackUrl();
            Map<String, Object> callbackPayload = buildCallbackPayload(eventLog, listener);

            return webClientBuilder.build()
                    .post()
                    .uri(callbackUrl)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .bodyValue(callbackPayload)
                    .retrieve()
                    .toEntity(String.class)
                    .flatMap(response -> {
                        eventLog.setStatus("success");
                        eventLog.setProcessedAt(LocalDateTime.now());
                        eventLog.setCallbackResponse(response.getBody());
                        eventLog.setCallbackAttempts(eventLog.getCallbackAttempts() + 1);
                        return updateEventLog(eventLog);
                    })
                    .onErrorResume(e -> {
                        eventLog.setCallbackAttempts(eventLog.getCallbackAttempts() + 1);
                        eventLog.setErrorDetail(e.getMessage());

                        if (eventLog.getCallbackAttempts() >= maxCallbackAttempts) {
                            eventLog.setStatus("failed");
                            eventLog.setProcessedAt(LocalDateTime.now());
                        } else {
                            eventLog.setStatus("retrying");
                        }

                        return updateEventLog(eventLog);
                    });
        });
    }

    @Scheduled(fixedRateString = "${nftindexer.event.retry-interval-ms:300000}")
    public void retryFailedCallbacks() {
        log.debug("Starting retry of failed event callbacks...");
        try {
            LambdaQueryWrapper<ContractEventLog> wrapper = new LambdaQueryWrapper<>();
            wrapper.in(ContractEventLog::getStatus, "retrying", "pending");
            wrapper.lt(ContractEventLog::getCallbackAttempts, maxCallbackAttempts);
            wrapper.and(w -> w.isNull(ContractEventLog::getProcessedAt)
                    .or()
                    .lt(ContractEventLog::getProcessedAt,
                            LocalDateTime.now().minusMinutes(retryIntervalMinutes)));
            wrapper.last("LIMIT 100");

            List<ContractEventLog> pendingLogs = eventLogMapper.selectList(wrapper);
            log.info("Found {} pending event logs for retry", pendingLogs.size());

            for (ContractEventLog eventLog : pendingLogs) {
                try {
                    LambdaQueryWrapper<ContractEventListener> listenerWrapper = new LambdaQueryWrapper<>();
                    listenerWrapper.eq(ContractEventListener::getListenerId, eventLog.getListenerId());
                    ContractEventListener listener = listenerMapper.selectOne(listenerWrapper);

                    if (listener != null && "active".equals(listener.getStatus())) {
                        executeCallback(eventLog, listener).subscribe();
                    }
                } catch (Exception e) {
                    log.error("Failed to retry callback for log {}: {}",
                            eventLog.getLogId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Event callback retry scheduler failed", e);
        }
    }

    public Mono<ContractEventLog> getEventLog(String logId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<ContractEventLog> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ContractEventLog::getLogId, logId);
            ContractEventLog eventLog = eventLogMapper.selectOne(wrapper);

            if (eventLog == null) {
                throw BusinessException.notFound("事件日志不存在: " + logId);
            }
            return eventLog;
        });
    }

    public Mono<Page<ContractEventLog>> listEventLogs(String listenerId, String chainId,
                                                       String status, String transactionHash,
                                                       int pageNum, int pageSize) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<ContractEventLog> wrapper = new LambdaQueryWrapper<>();
            if (listenerId != null && !listenerId.isEmpty()) {
                wrapper.eq(ContractEventLog::getListenerId, listenerId);
            }
            if (chainId != null && !chainId.isEmpty()) {
                wrapper.eq(ContractEventLog::getChainId, chainId);
            }
            if (status != null && !status.isEmpty()) {
                wrapper.eq(ContractEventLog::getStatus, status);
            }
            if (transactionHash != null && !transactionHash.isEmpty()) {
                wrapper.eq(ContractEventLog::getTransactionHash, transactionHash);
            }
            wrapper.orderByDesc(ContractEventLog::getBlockNumber);
            return eventLogMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        });
    }

    public Mono<Map<String, Object>> getEventListenerStats(String listenerId) {
        return Mono.fromCallable(() -> {
            Map<String, Object> stats = new HashMap<>();

            LambdaQueryWrapper<ContractEventLog> totalWrapper = new LambdaQueryWrapper<>();
            totalWrapper.eq(ContractEventLog::getListenerId, listenerId);
            Long total = eventLogMapper.selectCount(totalWrapper);

            LambdaQueryWrapper<ContractEventLog> successWrapper = new LambdaQueryWrapper<>();
            successWrapper.eq(ContractEventLog::getListenerId, listenerId);
            successWrapper.eq(ContractEventLog::getStatus, "success");
            Long success = eventLogMapper.selectCount(successWrapper);

            LambdaQueryWrapper<ContractEventLog> failedWrapper = new LambdaQueryWrapper<>();
            failedWrapper.eq(ContractEventLog::getListenerId, listenerId);
            failedWrapper.in(ContractEventLog::getStatus, "failed", "retrying");
            Long failed = eventLogMapper.selectCount(failedWrapper);

            LambdaQueryWrapper<ContractEventLog> latestWrapper = new LambdaQueryWrapper<>();
            latestWrapper.eq(ContractEventLog::getListenerId, listenerId);
            latestWrapper.orderByDesc(ContractEventLog::getBlockNumber);
            latestWrapper.last("LIMIT 1");
            ContractEventLog latest = eventLogMapper.selectOne(latestWrapper);

            stats.put("totalEvents", total);
            stats.put("successEvents", success);
            stats.put("failedEvents", failed);
            stats.put("successRate", total > 0 ?
                    (double) success / total * 100 : 0.0);
            stats.put("latestBlock", latest != null ? latest.getBlockNumber() : -1);
            stats.put("latestBlockTime", latest != null ? latest.getBlockTime() : null);

            return stats;
        });
    }

    private Map<String, Object> buildCallbackPayload(ContractEventLog eventLog, ContractEventListener listener) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("logId", eventLog.getLogId());
        payload.put("listenerId", eventLog.getListenerId());
        payload.put("chainId", eventLog.getChainId());
        payload.put("contractAddress", eventLog.getContractAddress());
        payload.put("transactionHash", eventLog.getTransactionHash());
        payload.put("blockNumber", eventLog.getBlockNumber());
        payload.put("blockHash", eventLog.getBlockHash());
        payload.put("blockTime", eventLog.getBlockTime());
        payload.put("eventName", eventLog.getEventName());
        payload.put("eventSignature", eventLog.getEventSignature());
        payload.put("topics", eventLog.getTopics());
        payload.put("decodedData", eventLog.getDecodedData());
        payload.put("rawData", eventLog.getRawData());
        payload.put("timestamp", LocalDateTime.now());
        return payload;
    }

    private Mono<ContractEventLog> updateEventLog(ContractEventLog eventLog) {
        return Mono.fromCallable(() -> {
            eventLogMapper.updateById(eventLog);
            return eventLog;
        });
    }

    private void updateListenerProgress(ContractEventListener listener, Integer blockNumber) {
        if (blockNumber != null && blockNumber > listener.getLastProcessedBlock()) {
            listener.setLastProcessedBlock(blockNumber);
            listener.setLastProcessedAt(LocalDateTime.now());
            listenerMapper.updateById(listener);

            LambdaQueryWrapper<RunInstance> runWrapper = new LambdaQueryWrapper<>();
            runWrapper.eq(RunInstance::getEntityId, listener.getListenerId());
            runWrapper.orderByDesc(RunInstance::getCreatedAt);
            runWrapper.last("LIMIT 1");
            RunInstance runInstance = runInstanceMapper.selectOne(runWrapper);

            if (runInstance != null && listener.getToBlock() != null && listener.getToBlock() > listener.getFromBlock()) {
                BigDecimal progress = new BigDecimal(blockNumber - listener.getFromBlock())
                        .divide(new BigDecimal(listener.getToBlock() - listener.getFromBlock()),
                                4, java.math.RoundingMode.HALF_UP);
                progress = progress.min(BigDecimal.ONE);
                runInstance.setProgress(progress);

                if (blockNumber >= listener.getToBlock()) {
                    runInstance.setPhase("completed");
                    runInstance.setCompletedAt(LocalDateTime.now());
                    listener.setStatus("completed");
                    listenerMapper.updateById(listener);
                }

                runInstanceMapper.updateById(runInstance);
            }
        }
    }

    private String generateEventSignature(String eventName, String abi) {
        try {
            String data = eventName + (abi != null ? abi : "");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder("0x");
            for (byte b : hash) {
                hexString.append(String.format("%02x", b));
            }
            return hexString.toString();
        } catch (Exception e) {
            return "0x" + UUID.randomUUID().toString().replace("-", "");
        }
    }

    private void emitEvent(String eventType, String aggregateId, String aggregateType,
                           Object payload, String traceId) {
        DomainEvent event = new DomainEvent();
        event.setEventId("evt-" + UUID.randomUUID().toString().substring(0, 8));
        event.setEventType(eventType);
        event.setAggregateId(aggregateId);
        event.setAggregateType(aggregateType);
        event.setPayload(Map.of("data", payload));
        event.setTimestamp(LocalDateTime.now());
        event.setTraceId(traceId);
        eventSink.tryEmitNext(event);
    }
}
