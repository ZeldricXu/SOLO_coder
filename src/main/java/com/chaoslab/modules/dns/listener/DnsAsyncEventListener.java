package com.chaoslab.modules.dns.listener;

import com.chaoslab.entity.DnsAsyncTask;
import com.chaoslab.event.DomainEvent;
import com.chaoslab.modules.dns.dto.DnsResolveResponse;
import com.chaoslab.modules.dns.event.DnsResolveEvent;
import com.chaoslab.mapper.DnsAsyncTaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DnsAsyncEventListener {

    private final DnsAsyncTaskMapper asyncTaskMapper;

    @Async
    @EventListener
    public void onDnsResolveEvent(DnsResolveEvent event) {
        log.info("Received DNS resolve event: {}, task: {}", event.getEventType(),
                event.getTask() != null ? event.getTask().getTaskId() : "unknown");

        switch (event.getEventType()) {
            case "DNS_RESOLVE_SUCCESS" -> handleSuccess(event);
            case "DNS_RESOLVE_FAILURE" -> handleFailure(event);
            case "DNS_RESOLVE_TIMEOUT" -> handleTimeout(event);
            case "DNS_RESOLVE_RETRY" -> handleRetry(event);
            default -> log.warn("Unknown DNS resolve event type: {}", event.getEventType());
        }

        publishDomainEvent(event);
    }

    private void handleSuccess(DnsResolveEvent event) {
        try {
            DnsAsyncTask task = event.getTask();
            DnsResolveResponse response = event.getResponse();

            if (task != null && response != null) {
                LambdaQueryWrapper<DnsAsyncTask> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(DnsAsyncTask::getTaskId, task.getTaskId());
                DnsAsyncTask existing = asyncTaskMapper.selectOne(wrapper);

                if (existing != null) {
                    existing.setStatus("COMPLETED");
                    existing.setCompletedAt(LocalDateTime.now());
                    asyncTaskMapper.updateById(existing);
                }

                log.info("DNS resolution successful for task: {}, domain: {}, answers: {}",
                        task.getTaskId(), task.getDomain(), response.getAnswers());
            }
        } catch (Exception e) {
            log.error("Error handling DNS success event", e);
        }
    }

    private void handleFailure(DnsResolveEvent event) {
        try {
            DnsAsyncTask task = event.getTask();
            Throwable error = event.getError();

            if (task != null) {
                LambdaQueryWrapper<DnsAsyncTask> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(DnsAsyncTask::getTaskId, task.getTaskId());
                DnsAsyncTask existing = asyncTaskMapper.selectOne(wrapper);

                if (existing != null) {
                    existing.setStatus("FAILED");
                    existing.setErrorMessage(error != null ? error.getMessage() : "Unknown error");
                    existing.setCompletedAt(LocalDateTime.now());
                    asyncTaskMapper.updateById(existing);
                }

                log.warn("DNS resolution failed for task: {}, domain: {}, error: {}",
                        task.getTaskId(), task.getDomain(),
                        error != null ? error.getMessage() : "Unknown");
            }
        } catch (Exception e) {
            log.error("Error handling DNS failure event", e);
        }
    }

    private void handleTimeout(DnsResolveEvent event) {
        try {
            DnsAsyncTask task = event.getTask();
            if (task != null) {
                log.warn("DNS resolution timeout for task: {}, domain: {}",
                        task.getTaskId(), task.getDomain());
            }
        } catch (Exception e) {
            log.error("Error handling DNS timeout event", e);
        }
    }

    private void handleRetry(DnsResolveEvent event) {
        try {
            DnsAsyncTask task = event.getTask();
            if (task != null) {
                log.info("DNS resolution retry for task: {}, domain: {}, retry count: {}",
                        task.getTaskId(), task.getDomain(), task.getRetryCount());
            }
        } catch (Exception e) {
            log.error("Error handling DNS retry event", e);
        }
    }

    private void publishDomainEvent(DnsResolveEvent event) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("eventType", event.getEventType());
            payload.put("timestamp", event.getTimestamp());

            if (event.getTask() != null) {
                payload.put("taskId", event.getTask().getTaskId());
                payload.put("domain", event.getTask().getDomain());
                payload.put("queryType", event.getTask().getQueryType());
                payload.put("context", event.getTask().getContext());

                if (event.getTask().getEventPayload() != null) {
                    payload.putAll(event.getTask().getEventPayload());
                }
            }

            if (event.getResponse() != null) {
                payload.put("answers", event.getResponse().getAnswers());
                payload.put("ttl", event.getResponse().getTtl());
                payload.put("upstreamId", event.getResponse().getUpstreamId());
            }

            if (event.getError() != null) {
                payload.put("error", event.getError().getMessage());
                payload.put("errorType", event.getError().getClass().getSimpleName());
            }

            String eventName = event.getTask() != null && event.getTask().getEventName() != null ?
                    event.getTask().getEventName() : event.getEventType();

            DomainEvent domainEvent = new DomainEvent(eventName, payload, "dns-async-service");
            log.debug("Published domain event: {}", eventName);

        } catch (Exception e) {
            log.error("Error publishing domain event", e);
        }
    }
}
