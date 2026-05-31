package com.chainetl.modules.events.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chainetl.common.exception.BusinessException;
import com.chainetl.common.util.IdGenerator;
import com.chainetl.modules.events.dto.CreateListenerRequest;
import com.chainetl.modules.events.dto.EventLogResponse;
import com.chainetl.modules.events.dto.ListenerResponse;
import com.chainetl.modules.events.dto.ProcessEventRequest;
import com.chainetl.modules.events.mapper.ContractEventListenerMapper;
import com.chainetl.modules.events.mapper.EventLogMapper;
import com.chainetl.modules.events.model.ContractEventListener;
import com.chainetl.modules.events.model.EventLog;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContractEventListenerService {

    private final ContractEventListenerMapper listenerMapper;
    private final EventLogMapper eventLogMapper;
    private final WebClient.Builder webClientBuilder;

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_STOPPED = "STOPPED";
    private static final String STATUS_ERROR = "ERROR";

    @Transactional
    @Retry(name = "events", fallbackMethod = "createListenerFallback")
    @Timed(value = "events.listener.create", description = "Time taken to create event listener")
    public Mono<ListenerResponse> createListener(CreateListenerRequest request) {
        return Mono.fromCallable(() -> {
            String listenerId = IdGenerator.generateListenerId();

            ContractEventListener listener = ContractEventListener.builder()
                    .listenerId(listenerId)
                    .chainId(request.getChainId())
                    .contractAddress(request.getContractAddress().toLowerCase())
                    .eventSignature(request.getEventSignature())
                    .callbackUrl(request.getCallbackUrl())
                    .startBlock(request.getStartBlock())
                    .status(STATUS_STOPPED)
                    .lastProcessedBlock(request.getStartBlock() - 1)
                    .createdAt(Instant.now())
                    .build();

            listenerMapper.insert(listener);
            log.info("Created event listener: {}", listenerId);

            return toListenerResponse(listener);
        });
    }

    @Transactional
    @Retry(name = "events", fallbackMethod = "startListenerFallback")
    @Timed(value = "events.listener.start", description = "Time taken to start event listener")
    public Mono<ListenerResponse> startListener(String listenerId) {
        return Mono.fromCallable(() -> {
            ContractEventListener listener = listenerMapper.selectById(listenerId);
            if (listener == null) {
                throw new BusinessException(404, "Listener not found: " + listenerId);
            }

            listener.setStatus(STATUS_ACTIVE);
            listenerMapper.updateById(listener);
            log.info("Started event listener: {}", listenerId);

            return toListenerResponse(listener);
        });
    }

    @Transactional
    @Retry(name = "events", fallbackMethod = "stopListenerFallback")
    @Timed(value = "events.listener.stop", description = "Time taken to stop event listener")
    public Mono<ListenerResponse> stopListener(String listenerId) {
        return Mono.fromCallable(() -> {
            ContractEventListener listener = listenerMapper.selectById(listenerId);
            if (listener == null) {
                throw new BusinessException(404, "Listener not found: " + listenerId);
            }

            listener.setStatus(STATUS_STOPPED);
            listenerMapper.updateById(listener);
            log.info("Stopped event listener: {}", listenerId);

            return toListenerResponse(listener);
        });
    }

    @Transactional
    @Retry(name = "events", fallbackMethod = "deleteListenerFallback")
    @Timed(value = "events.listener.delete", description = "Time taken to delete event listener")
    public Mono<Void> deleteListener(String listenerId) {
        return Mono.fromCallable(() -> {
            ContractEventListener listener = listenerMapper.selectById(listenerId);
            if (listener == null) {
                throw new BusinessException(404, "Listener not found: " + listenerId);
            }

            listenerMapper.deleteById(listenerId);
            log.info("Deleted event listener: {}", listenerId);

            return null;
        });
    }

    @Retry(name = "events", fallbackMethod = "getListenerFallback")
    @Timed(value = "events.listener.get", description = "Time taken to get event listener")
    public Mono<ListenerResponse> getListener(String listenerId) {
        return Mono.fromCallable(() -> {
            ContractEventListener listener = listenerMapper.selectById(listenerId);
            if (listener == null) {
                throw new BusinessException(404, "Listener not found: " + listenerId);
            }
            return toListenerResponse(listener);
        });
    }

    @Retry(name = "events", fallbackMethod = "listListenersFallback")
    @Timed(value = "events.listener.list", description = "Time taken to list event listeners")
    public Mono<List<ListenerResponse>> listListeners(String chainId, String status) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<ContractEventListener> wrapper = new LambdaQueryWrapper<>();
            if (chainId != null) {
                wrapper.eq(ContractEventListener::getChainId, chainId);
            }
            if (status != null) {
                wrapper.eq(ContractEventListener::getStatus, status.toUpperCase());
            }
            wrapper.orderByDesc(ContractEventListener::getCreatedAt);

            List<ContractEventListener> listeners = listenerMapper.selectList(wrapper);
            return listeners.stream()
                    .map(this::toListenerResponse)
                    .collect(Collectors.toList());
        });
    }

    @Transactional
    @Retry(name = "events", fallbackMethod = "processEventLogsFallback")
    @Timed(value = "events.logs.process", description = "Time taken to process event logs")
    public Mono<Integer> processEventLogs() {
        return Mono.fromCallable(() -> {
            List<ContractEventListener> activeListeners = listenerMapper.selectList(
                    new LambdaQueryWrapper<ContractEventListener>()
                            .eq(ContractEventListener::getStatus, STATUS_ACTIVE)
            );

            int processedCount = 0;

            for (ContractEventListener listener : activeListeners) {
                LambdaQueryWrapper<EventLog> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(EventLog::getChainId, listener.getChainId())
                        .eq(EventLog::getContractAddress, listener.getContractAddress())
                        .eq(EventLog::getEventSignature, listener.getEventSignature())
                        .eq(EventLog::getProcessed, false)
                        .gt(EventLog::getBlockNumber, listener.getLastProcessedBlock())
                        .orderByAsc(EventLog::getBlockNumber)
                        .orderByAsc(EventLog::getLogIndex);

                List<EventLog> unprocessedLogs = eventLogMapper.selectList(wrapper);

                for (EventLog eventLog : unprocessedLogs) {
                    try {
                        String callbackResult = sendCallback(listener.getCallbackUrl(), eventLog).block();

                        eventLog.setProcessed(true);
                        eventLog.setProcessedAt(Instant.now());
                        eventLogMapper.updateById(eventLog);

                        listener.setLastProcessedBlock(eventLog.getBlockNumber());
                        listenerMapper.updateById(listener);

                        processedCount++;
                        log.info("Processed event log {} for listener {}", eventLog.getLogId(), listener.getListenerId());
                    } catch (Exception e) {
                        log.error("Failed to process event log {} for listener {}: {}",
                                eventLog.getLogId(), listener.getListenerId(), e.getMessage());
                        listener.setStatus(STATUS_ERROR);
                        listenerMapper.updateById(listener);
                        break;
                    }
                }
            }

            return processedCount;
        });
    }

    @Transactional
    @Retry(name = "events", fallbackMethod = "processSingleEventFallback")
    @Timed(value = "events.log.process.single", description = "Time taken to process single event log")
    public Mono<EventLogResponse> processSingleEvent(ProcessEventRequest request) {
        return Mono.fromCallable(() -> {
            EventLog eventLog = eventLogMapper.selectById(request.getLogId());
            if (eventLog == null) {
                throw new BusinessException(404, "Event log not found: " + request.getLogId());
            }

            LambdaQueryWrapper<ContractEventListener> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ContractEventListener::getChainId, eventLog.getChainId())
                    .eq(ContractEventListener::getContractAddress, eventLog.getContractAddress())
                    .eq(ContractEventListener::getEventSignature, eventLog.getEventSignature())
                    .last("LIMIT 1");

            ContractEventListener listener = listenerMapper.selectOne(wrapper);
            if (listener == null) {
                throw new BusinessException(404, "No listener found for event log: " + request.getLogId());
            }

            try {
                String callbackResult = request.getCallbackResult() != null
                        ? request.getCallbackResult()
                        : sendCallback(listener.getCallbackUrl(), eventLog).block();

                eventLog.setProcessed(true);
                eventLog.setProcessedAt(Instant.now());
                eventLogMapper.updateById(eventLog);

                if (eventLog.getBlockNumber() > listener.getLastProcessedBlock()) {
                    listener.setLastProcessedBlock(eventLog.getBlockNumber());
                    listenerMapper.updateById(listener);
                }

                log.info("Manually processed event log {} for listener {}", eventLog.getLogId(), listener.getListenerId());
                return toEventLogResponse(eventLog);
            } catch (Exception e) {
                log.error("Failed to manually process event log {}: {}", eventLog.getLogId(), e.getMessage());
                throw new BusinessException("Failed to process event: " + e.getMessage());
            }
        });
    }

    @Retry(name = "events", fallbackMethod = "getEventLogsFallback")
    @Timed(value = "events.logs.get", description = "Time taken to get event logs")
    public Mono<List<EventLogResponse>> getEventLogs(String chainId, String contractAddress, Boolean processed, Long fromBlock, Long toBlock) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<EventLog> wrapper = new LambdaQueryWrapper<>();
            if (chainId != null) {
                wrapper.eq(EventLog::getChainId, chainId);
            }
            if (contractAddress != null) {
                wrapper.eq(EventLog::getContractAddress, contractAddress.toLowerCase());
            }
            if (processed != null) {
                wrapper.eq(EventLog::getProcessed, processed);
            }
            if (fromBlock != null) {
                wrapper.ge(EventLog::getBlockNumber, fromBlock);
            }
            if (toBlock != null) {
                wrapper.le(EventLog::getBlockNumber, toBlock);
            }
            wrapper.orderByDesc(EventLog::getBlockNumber)
                    .orderByDesc(EventLog::getLogIndex);

            List<EventLog> eventLogs = eventLogMapper.selectList(wrapper);
            return eventLogs.stream()
                    .map(this::toEventLogResponse)
                    .collect(Collectors.toList());
        });
    }

    private Mono<String> sendCallback(String callbackUrl, EventLog eventLog) {
        return webClientBuilder.build()
                .post()
                .uri(callbackUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(toEventLogResponse(eventLog))
                .retrieve()
                .onStatus(HttpStatus::isError, clientResponse ->
                        Mono.error(new BusinessException("Callback failed with status: " + clientResponse.statusCode()))
                )
                .bodyToMono(String.class);
    }

    private ListenerResponse toListenerResponse(ContractEventListener listener) {
        return ListenerResponse.builder()
                .listenerId(listener.getListenerId())
                .chainId(listener.getChainId())
                .contractAddress(listener.getContractAddress())
                .eventSignature(listener.getEventSignature())
                .callbackUrl(listener.getCallbackUrl())
                .startBlock(listener.getStartBlock())
                .status(listener.getStatus())
                .lastProcessedBlock(listener.getLastProcessedBlock())
                .createdAt(listener.getCreatedAt())
                .build();
    }

    private EventLogResponse toEventLogResponse(EventLog eventLog) {
        return EventLogResponse.builder()
                .logId(eventLog.getLogId())
                .chainId(eventLog.getChainId())
                .blockNumber(eventLog.getBlockNumber())
                .txHash(eventLog.getTxHash())
                .logIndex(eventLog.getLogIndex())
                .contractAddress(eventLog.getContractAddress())
                .eventSignature(eventLog.getEventSignature())
                .topics(eventLog.getTopics())
                .data(eventLog.getData())
                .processed(eventLog.getProcessed())
                .processedAt(eventLog.getProcessedAt())
                .build();
    }

    private Mono<ListenerResponse> createListenerFallback(CreateListenerRequest request, Exception e) {
        log.error("Create listener fallback triggered: {}", e.getMessage(), e);
        throw new BusinessException("Failed to create listener after retries: " + e.getMessage());
    }

    private Mono<ListenerResponse> startListenerFallback(String listenerId, Exception e) {
        log.error("Start listener fallback triggered: {}", e.getMessage(), e);
        throw new BusinessException("Failed to start listener after retries: " + e.getMessage());
    }

    private Mono<ListenerResponse> stopListenerFallback(String listenerId, Exception e) {
        log.error("Stop listener fallback triggered: {}", e.getMessage(), e);
        throw new BusinessException("Failed to stop listener after retries: " + e.getMessage());
    }

    private Mono<Void> deleteListenerFallback(String listenerId, Exception e) {
        log.error("Delete listener fallback triggered: {}", e.getMessage(), e);
        throw new BusinessException("Failed to delete listener after retries: " + e.getMessage());
    }

    private Mono<ListenerResponse> getListenerFallback(String listenerId, Exception e) {
        log.error("Get listener fallback triggered: {}", e.getMessage(), e);
        throw new BusinessException("Failed to get listener after retries: " + e.getMessage());
    }

    private Mono<List<ListenerResponse>> listListenersFallback(String chainId, String status, Exception e) {
        log.error("List listeners fallback triggered: {}", e.getMessage(), e);
        throw new BusinessException("Failed to list listeners after retries: " + e.getMessage());
    }

    private Mono<Integer> processEventLogsFallback(Exception e) {
        log.error("Process event logs fallback triggered: {}", e.getMessage(), e);
        throw new BusinessException("Failed to process event logs after retries: " + e.getMessage());
    }

    private Mono<EventLogResponse> processSingleEventFallback(ProcessEventRequest request, Exception e) {
        log.error("Process single event fallback triggered: {}", e.getMessage(), e);
        throw new BusinessException("Failed to process event after retries: " + e.getMessage());
    }

    private Mono<List<EventLogResponse>> getEventLogsFallback(String chainId, String contractAddress, Boolean processed, Long fromBlock, Long toBlock, Exception e) {
        log.error("Get event logs fallback triggered: {}", e.getMessage(), e);
        throw new BusinessException("Failed to get event logs after retries: " + e.getMessage());
    }
}
