package com.didauth.core.audit;

import com.didauth.core.entity.AuditLog;
import com.didauth.core.mapper.AuditLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogMapper auditLogMapper;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    public Mono<Void> recordAuditLog(String traceId, String userId, String module, String operation,
                                     Object requestParams, Object responseResult, String status,
                                     String errorMessage, String ipAddress, String userAgent, Long durationMs) {
        return Mono.fromCallable(() -> {
            AuditLog auditLog = new AuditLog();
            auditLog.setTraceId(traceId);
            auditLog.setUserId(userId);
            auditLog.setModule(module);
            auditLog.setOperation(operation);

            try {
                if (requestParams != null) {
                    auditLog.setRequestParams(objectMapper.writeValueAsString(requestParams));
                }
                if (responseResult != null) {
                    auditLog.setResponseResult(objectMapper.writeValueAsString(responseResult));
                }
            } catch (Exception e) {
                log.warn("Failed to serialize audit log params", e);
            }

            auditLog.setStatus(status);
            auditLog.setErrorMessage(errorMessage);
            auditLog.setIpAddress(ipAddress);
            auditLog.setUserAgent(userAgent);
            auditLog.setDurationMs(durationMs);

            auditLogMapper.insert(auditLog);

            meterRegistry.counter("audit.log.count", "module", module, "status", status).increment();

            return null;
        }).subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.error("Failed to record audit log", e);
                    return Mono.empty();
                }).then();
    }
}
