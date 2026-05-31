package com.apishield.audit.service.impl;

import com.apishield.common.exception.BusinessException;
import com.apishield.common.util.CryptoUtil;
import com.apishield.common.util.IdGenerator;
import com.apishield.audit.domain.AuditLog;
import com.apishield.audit.dto.AuditLogRequest;
import com.apishield.audit.dto.AuditVerifyRequest;
import com.apishield.audit.dto.AuditVerifyResult;
import com.apishield.audit.service.AuditLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AuditLogServiceImpl implements AuditLogService {

    private final Map<String, AuditLog> logStore = new ConcurrentHashMap<>();
    private final AtomicInteger blockHeight = new AtomicInteger(0);
    private volatile String lastHash = "GENESIS_BLOCK_HASH";

    @Override
    public AuditLog createLog(AuditLogRequest request) {
        AuditLog auditLog = new AuditLog();
        auditLog.setId(IdGenerator.generateId("audit"));
        auditLog.setLogId(auditLog.getId());
        auditLog.setOperation(request.getOperation());
        auditLog.setOperatorId(request.getOperatorId());
        auditLog.setOperatorName(request.getOperatorName());
        auditLog.setResourceType(request.getResourceType());
        auditLog.setResourceId(request.getResourceId());
        auditLog.setRequestParams(request.getRequestParams());
        auditLog.setResponseResult(request.getResponseResult());
        auditLog.setStatus(request.getStatus());
        auditLog.setIpAddress(request.getIpAddress());
        auditLog.setUserAgent(request.getUserAgent());
        auditLog.setTimestamp(System.currentTimeMillis());
        auditLog.setPreviousHash(lastHash);
        auditLog.setBlockHeight(blockHeight.incrementAndGet());
        auditLog.setCreatedAt(LocalDateTime.now());
        auditLog.setUpdatedAt(LocalDateTime.now());

        String currentHash = calculateHash(auditLog);
        auditLog.setCurrentHash(currentHash);
        lastHash = currentHash;

        logStore.put(auditLog.getLogId(), auditLog);
        log.info("Created audit log: {}, operation: {}, operator: {}", 
                auditLog.getLogId(), request.getOperation(), request.getOperatorId());

        return auditLog;
    }

    @Override
    public AuditLog getLogById(String logId) {
        AuditLog auditLog = logStore.get(logId);
        if (auditLog == null) {
            throw new BusinessException("NOT_FOUND", "审计日志不存在: " + logId);
        }
        return auditLog;
    }

    @Override
    public List<AuditLog> getLogsByOperator(String operatorId, int page, int size) {
        return logStore.values().stream()
                .filter(l -> operatorId.equals(l.getOperatorId()))
                .sorted((a, b) -> Long.compare(b.getTimestamp(), a.getTimestamp()))
                .skip((long) (page - 1) * size)
                .limit(size)
                .collect(Collectors.toList());
    }

    @Override
    public List<AuditLog> getLogsByResource(String resourceType, String resourceId) {
        return logStore.values().stream()
                .filter(l -> resourceType.equals(l.getResourceType()) && resourceId.equals(l.getResourceId()))
                .sorted((a, b) -> Long.compare(b.getTimestamp(), a.getTimestamp()))
                .collect(Collectors.toList());
    }

    @Override
    public AuditVerifyResult verifyIntegrity(AuditVerifyRequest request) {
        List<String> tamperedIds = new ArrayList<>();
        List<AuditLog> logsToVerify;

        if (request.getLogIds() != null && !request.getLogIds().isEmpty()) {
            logsToVerify = request.getLogIds().stream()
                    .map(logStore::get)
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparingInt(AuditLog::getBlockHeight))
                    .collect(Collectors.toList());
        } else {
            logsToVerify = logStore.values().stream()
                    .filter(l -> l.getBlockHeight() >= request.getStartHeight() && l.getBlockHeight() <= request.getEndHeight())
                    .sorted(Comparator.comparingInt(AuditLog::getBlockHeight))
                    .collect(Collectors.toList());
        }

        for (AuditLog logEntry : logsToVerify) {
            String recalculatedHash = calculateHash(logEntry);
            if (!recalculatedHash.equals(logEntry.getCurrentHash())) {
                tamperedIds.add(logEntry.getLogId());
                log.warn("Tampered log detected: {}", logEntry.getLogId());
            }
        }

        AuditVerifyResult result = new AuditVerifyResult();
        result.setValid(tamperedIds.isEmpty());
        result.setTamperedLogIds(tamperedIds);
        result.setVerifiedCount(logsToVerify.size());
        result.setTamperedCount(tamperedIds.size());
        result.setMessage(tamperedIds.isEmpty() ? "完整性验证通过" : "发现" + tamperedIds.size() + "条被篡改的日志");

        return result;
    }

    @Override
    public AuditVerifyResult verifyFullChain() {
        List<AuditLog> allLogs = logStore.values().stream()
                .sorted(Comparator.comparingInt(AuditLog::getBlockHeight))
                .collect(Collectors.toList());

        List<String> tamperedIds = new ArrayList<>();
        String expectedPreviousHash = "GENESIS_BLOCK_HASH";

        for (AuditLog logEntry : allLogs) {
            if (!expectedPreviousHash.equals(logEntry.getPreviousHash())) {
                tamperedIds.add(logEntry.getLogId());
                log.warn("Hash chain broken at block: {}", logEntry.getBlockHeight());
            }

            String recalculatedHash = calculateHash(logEntry);
            if (!recalculatedHash.equals(logEntry.getCurrentHash())) {
                tamperedIds.add(logEntry.getLogId());
                log.warn("Tampered log detected: {}", logEntry.getLogId());
            }

            expectedPreviousHash = logEntry.getCurrentHash();
        }

        AuditVerifyResult result = new AuditVerifyResult();
        result.setValid(tamperedIds.isEmpty());
        result.setTamperedLogIds(tamperedIds);
        result.setVerifiedCount(allLogs.size());
        result.setTamperedCount(tamperedIds.size());
        result.setMessage(tamperedIds.isEmpty() ? "完整哈希链验证通过" : "哈希链断裂或发现篡改");

        return result;
    }

    @Override
    public List<AuditLog> getLogsByTimeRange(long startTime, long endTime) {
        return logStore.values().stream()
                .filter(l -> l.getTimestamp() >= startTime && l.getTimestamp() <= endTime)
                .sorted((a, b) -> Long.compare(b.getTimestamp(), a.getTimestamp()))
                .collect(Collectors.toList());
    }

    private String calculateHash(AuditLog log) {
        String content = log.getLogId() + "|" +
                log.getOperation() + "|" +
                log.getOperatorId() + "|" +
                log.getResourceType() + "|" +
                log.getResourceId() + "|" +
                log.getTimestamp() + "|" +
                log.getPreviousHash();
        return CryptoUtil.sha256(content);
    }
}
