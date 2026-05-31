package com.contraudit.listener.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.contraudit.common.BusinessException;
import com.contraudit.common.ErrorCode;
import com.contraudit.listener.entity.EventLog;
import com.contraudit.listener.entity.EventListener;
import com.contraudit.listener.mapper.EventLogMapper;
import com.contraudit.listener.mapper.EventListenerMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventListenerService {

    private final EventListenerMapper listenerMapper;
    private final EventLogMapper eventLogMapper;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    private final Map<String, Boolean> runningListeners = new ConcurrentHashMap<>();

    @Transactional(rollbackFor = Exception.class)
    public EventListener createListener(EventListener listener) {
        if (listener.getStartBlock() == null) {
            listener.setStartBlock(0L);
        }
        if (listener.getCurrentBlock() == null) {
            listener.setCurrentBlock(listener.getStartBlock());
        }
        if (listener.getRetryCount() == null) {
            listener.setRetryCount(3);
        }
        if (listener.getRetryInterval() == null) {
            listener.setRetryInterval(5000);
        }
        listener.setStatus(0);
        listenerMapper.insert(listener);
        log.info("Created event listener: {} - {}", listener.getId(), listener.getListenerName());
        return listener;
    }

    public EventListener getListener(String id) {
        EventListener listener = listenerMapper.selectById(id);
        if (listener == null) {
            throw new BusinessException(ErrorCode.EVENT_LISTENER_NOT_FOUND);
        }
        return listener;
    }

    public List<EventListener> listListeners(String chainType, String contractAddress, Integer status) {
        LambdaQueryWrapper<EventListener> wrapper = new LambdaQueryWrapper<>();
        if (chainType != null) {
            wrapper.eq(EventListener::getChainType, chainType);
        }
        if (contractAddress != null) {
            wrapper.eq(EventListener::getContractAddress, contractAddress);
        }
        if (status != null) {
            wrapper.eq(EventListener::getStatus, status);
        }
        wrapper.orderByDesc(EventListener::getCreatedAt);
        return listenerMapper.selectList(wrapper);
    }

    @Transactional(rollbackFor = Exception.class)
    public EventListener startListener(String id) {
        EventListener listener = getListener(id);
        if (listener.getStatus() == 1) {
            return listener;
        }

        listener.setStatus(1);
        listenerMapper.updateById(listener);
        runningListeners.put(id, true);

        log.info("Started event listener: {}", id);

        return listener;
    }

    @Transactional(rollbackFor = Exception.class)
    public EventListener stopListener(String id) {
        EventListener listener = getListener(id);
        listener.setStatus(0);
        listenerMapper.updateById(listener);
        runningListeners.remove(id);
        log.info("Stopped event listener: {}", id);
        return listener;
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteListener(String id) {
        stopListener(id);
        listenerMapper.deleteById(id);
        log.info("Deleted event listener: {}", id);
    }

    @Transactional(rollbackFor = Exception.class)
    public EventLog recordEvent(String listenerId, String chainType, String contractAddress,
                                 String eventName, String txHash, Long blockNumber,
                                 String blockHash, Integer logIndex,
                                 String eventData, String decodedData) {
        EventLog eventLog = new EventLog();
        eventLog.setListenerId(listenerId);
        eventLog.setChainType(chainType);
        eventLog.setContractAddress(contractAddress);
        eventLog.setEventName(eventName);
        eventLog.setTxHash(txHash);
        eventLog.setBlockNumber(blockNumber);
        eventLog.setBlockHash(blockHash);
        eventLog.setLogIndex(logIndex);
        eventLog.setEventData(eventData);
        eventLog.setDecodedData(decodedData);
        eventLog.setStatus("RECEIVED");

        eventLogMapper.insert(eventLog);

        log.info("Recorded event: {} - {} at block {}", eventName, txHash, blockNumber);

        return eventLog;
    }

    @Transactional(rollbackFor = Exception.class)
    public EventLog processEvent(String eventLogId) {
        EventLog eventLog = getEventLog(eventLogId);
        eventLog.setStatus("PROCESSED");
        eventLog.setProcessedAt(LocalDateTime.now());
        eventLogMapper.updateById(eventLog);
        log.info("Processed event log: {}", eventLogId);
        return eventLog;
    }

    @Transactional(rollbackFor = Exception.class)
    public EventLog triggerCallback(String eventLogId) {
        EventLog eventLog = getEventLog(eventLogId);
        EventListener listener = getListener(eventLog.getListenerId());

        if (listener.getCallbackUrl() == null || listener.getCallbackUrl().isEmpty()) {
            eventLog.setStatus("CALLBACK_SKIPPED");
            eventLogMapper.updateById(eventLog);
            return eventLog;
        }

        try {
            String response = executeCallback(listener, eventLog);
            eventLog.setStatus("CALLBACK");
            eventLog.setCallbackStatus("SUCCESS");
            eventLog.setCallbackResponse(response);
            log.info("Callback triggered successfully for event: {}", eventLogId);
        } catch (Exception e) {
            log.error("Callback failed for event: {}", eventLogId, e);
            eventLog.setStatus("CALLBACK_FAILED");
            eventLog.setCallbackStatus("FAILED");
            eventLog.setErrorMessage(e.getMessage());
        }

        eventLogMapper.updateById(eventLog);
        return eventLog;
    }

    public EventLog getEventLog(String id) {
        EventLog eventLog = eventLogMapper.selectById(id);
        if (eventLog == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "event log not found");
        }
        return eventLog;
    }

    public List<EventLog> listEventLogs(String listenerId, String status,
                                        Long fromBlock, Long toBlock, String txHash) {
        LambdaQueryWrapper<EventLog> wrapper = new LambdaQueryWrapper<>();
        if (listenerId != null) {
            wrapper.eq(EventLog::getListenerId, listenerId);
        }
        if (status != null) {
            wrapper.eq(EventLog::getStatus, status);
        }
        if (fromBlock != null) {
            wrapper.ge(EventLog::getBlockNumber, fromBlock);
        }
        if (toBlock != null) {
            wrapper.le(EventLog::getBlockNumber, toBlock);
        }
        if (txHash != null) {
            wrapper.eq(EventLog::getTxHash, txHash);
        }
        wrapper.orderByDesc(EventLog::getBlockNumber);
        wrapper.orderByDesc(EventLog::getLogIndex);
        return eventLogMapper.selectList(wrapper);
    }

    private String executeCallback(EventListener listener, EventLog eventLog) throws Exception {
        String url = listener.getCallbackUrl();
        String method = listener.getCallbackMethod() != null ?
                listener.getCallbackMethod().toUpperCase() : "POST";

        Map<String, Object> payload = Map.of(
                "eventId", eventLog.getId(),
                "listenerId", listener.getId(),
                "eventName", eventLog.getEventName(),
                "contractAddress", eventLog.getContractAddress(),
                "txHash", eventLog.getTxHash(),
                "blockNumber", eventLog.getBlockNumber(),
                "data", eventLog.getDecodedData() != null ? eventLog.getDecodedData() : eventLog.getEventData(),
                "timestamp", System.currentTimeMillis()
        );

        int retryCount = listener.getRetryCount() != null ? listener.getRetryCount() : 3;
        int retryInterval = listener.getRetryInterval() != null ? listener.getRetryInterval() : 5000;

        Exception lastException = null;

        for (int i = 0; i < retryCount; i++) {
            try {
                WebClient.RequestHeadersSpec<?> requestSpec;

                if ("GET".equals(method)) {
                    requestSpec = webClientBuilder.build().get().uri(url);
                } else {
                    requestSpec = webClientBuilder.build()
                            .post()
                            .uri(url)
                            .bodyValue(objectMapper.writeValueAsString(payload));
                }

                return requestSpec
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();
            } catch (Exception e) {
                lastException = e;
                log.warn("Callback attempt {} failed for event: {}", i + 1, eventLog.getId(), e);
                if (i < retryCount - 1) {
                    Thread.sleep(retryInterval);
                }
            }
        }

        throw lastException != null ? lastException : new RuntimeException("Callback failed");
    }

    public boolean isListenerRunning(String listenerId) {
        return runningListeners.getOrDefault(listenerId, false);
    }

    @Transactional(rollbackFor = Exception.class)
    public EventListener updateCurrentBlock(String listenerId, Long blockNumber) {
        EventListener listener = getListener(listenerId);
        listener.setCurrentBlock(blockNumber);
        listenerMapper.updateById(listener);
        return listener;
    }
}
