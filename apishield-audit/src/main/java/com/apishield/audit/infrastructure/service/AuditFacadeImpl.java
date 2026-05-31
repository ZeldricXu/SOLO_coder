package com.apishield.audit.infrastructure.service;

import com.apishield.common.util.IdGenerator;
import com.apishield.audit.api.AuditFacade;
import com.apishield.audit.domain.model.AuditLog;
import com.apishield.audit.domain.model.AuditVerifyResult;
import com.apishield.audit.domain.repository.AuditLogRepository;
import com.apishield.audit.domain.service.AuditVerificationService;
import com.apishield.audit.domain.service.HashChainService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditFacadeImpl implements AuditFacade {

    private final HashChainService hashChainService;
    private final AuditVerificationService verificationService;
    private final AuditLogRepository auditLogRepository;

    @Override
    public AuditLog createLog(String operation, String operatorId, String operatorName,
                               String resourceType, String resourceId, String requestParams,
                               String responseResult, String status, String ipAddress, String userAgent) {
        String logId = IdGenerator.generateId("audit");
        long timestamp = System.currentTimeMillis();
        
        String currentHash = hashChainService.getNextHash(
                logId, operation, operatorId, resourceType, resourceId, timestamp);

        AuditLog auditLog = new AuditLog();
        auditLog.setId(logId);
        auditLog.setLogId(logId);
        auditLog.setOperation(operation);
        auditLog.setOperatorId(operatorId);
        auditLog.setOperatorName(operatorName);
        auditLog.setResourceType(resourceType);
        auditLog.setResourceId(resourceId);
        auditLog.setRequestParams(requestParams);
        auditLog.setResponseResult(responseResult);
        auditLog.setStatus(status);
        auditLog.setIpAddress(ipAddress);
        auditLog.setUserAgent(userAgent);
        auditLog.setTimestamp(timestamp);
        auditLog.setPreviousHash(hashChainService.getLastHash());
        auditLog.setCurrentHash(currentHash);
        auditLog.setBlockHeight(hashChainService.getCurrentBlockHeight());
        auditLog.setCreatedAt(LocalDateTime.now());

        auditLogRepository.save(auditLog);
        log.info("Created audit log: {}, operation: {}, operator: {}", logId, operation, operatorId);
        return auditLog;
    }

    @Override
    public Optional<AuditLog> findById(String logId) {
        return auditLogRepository.findById(logId);
    }

    @Override
    public List<AuditLog> findByOperator(String operatorId, int page, int size) {
        return auditLogRepository.findByOperatorId(operatorId, page, size);
    }

    @Override
    public List<AuditLog> findByResource(String resourceType, String resourceId) {
        return auditLogRepository.findByResource(resourceType, resourceId);
    }

    @Override
    public List<AuditLog> findByTimeRange(long startTime, long endTime) {
        return auditLogRepository.findByTimeRange(startTime, endTime);
    }

    @Override
    public List<AuditLog> findByOperation(String operation, int page, int size) {
        return auditLogRepository.findByOperation(operation, page, size);
    }

    @Override
    public AuditVerifyResult verifyLogIntegrity(List<String> logIds) {
        List<AuditLog> logs = auditLogRepository.findByIds(logIds);
        return verificationService.verifyLogs(logs);
    }

    @Override
    public AuditVerifyResult verifyBlockRange(int startHeight, int endHeight) {
        List<AuditLog> logs = auditLogRepository.findByBlockHeightRange(startHeight, endHeight);
        return verificationService.verifyLogs(logs);
    }

    @Override
    public AuditVerifyResult verifyFullChain() {
        List<AuditLog> allLogs = auditLogRepository.findAll();
        return verificationService.verifyFullChain(allLogs);
    }

    @Override
    public boolean verifySingleLog(String logId) {
        return auditLogRepository.findById(logId)
                .map(verificationService::verifySingleLog)
                .orElse(false);
    }

    @Override
    public String getLastHash() {
        return hashChainService.getLastHash();
    }

    @Override
    public int getCurrentBlockHeight() {
        return hashChainService.getCurrentBlockHeight();
    }

    @Override
    public String calculateHash(String logId, String operation, String operatorId,
                                 String resourceType, String resourceId, long timestamp,
                                 String previousHash) {
        return hashChainService.calculateHash(logId, operation, operatorId, resourceType,
                                                resourceId, timestamp, previousHash);
    }

    @Override
    public void resetChain() {
        hashChainService.resetChain();
    }
}
