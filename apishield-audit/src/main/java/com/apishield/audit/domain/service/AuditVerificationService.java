package com.apishield.audit.domain.service;

import com.apishield.audit.domain.model.AuditLog;
import com.apishield.audit.domain.model.AuditVerifyResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditVerificationService {

    private final HashChainService hashChainService;

    public AuditVerifyResult verifyLogs(List<AuditLog> logs) {
        List<String> tamperedIds = new ArrayList<>();
        
        for (AuditLog auditLog : logs) {
            String recalculatedHash = hashChainService.calculateHash(
                    auditLog.getLogId(),
                    auditLog.getOperation(),
                    auditLog.getOperatorId(),
                    auditLog.getResourceType(),
                    auditLog.getResourceId(),
                    auditLog.getTimestamp(),
                    auditLog.getPreviousHash()
            );
            
            if (!recalculatedHash.equals(auditLog.getCurrentHash())) {
                tamperedIds.add(auditLog.getLogId());
                log.warn("Tampered log detected: {}", auditLog.getLogId());
            }
        }

        AuditVerifyResult result = new AuditVerifyResult();
        result.setValid(tamperedIds.isEmpty());
        result.setTamperedLogIds(tamperedIds);
        result.setVerifiedCount(logs.size());
        result.setTamperedCount(tamperedIds.size());
        result.setMessage(tamperedIds.isEmpty() ? "完整性验证通过" : 
                "发现" + tamperedIds.size() + "条被篡改的日志");
        
        return result;
    }

    public AuditVerifyResult verifyFullChain(List<AuditLog> allLogs) {
        List<AuditLog> sortedLogs = allLogs.stream()
                .sorted(Comparator.comparingInt(AuditLog::getBlockHeight))
                .toList();

        List<String> tamperedIds = new ArrayList<>();
        String expectedPreviousHash = "GENESIS_BLOCK_HASH";

        for (AuditLog auditLog : sortedLogs) {
            if (!expectedPreviousHash.equals(auditLog.getPreviousHash())) {
                tamperedIds.add(auditLog.getLogId());
                log.warn("Hash chain broken at block: {}", auditLog.getBlockHeight());
            }

            String recalculatedHash = hashChainService.calculateHash(
                    auditLog.getLogId(),
                    auditLog.getOperation(),
                    auditLog.getOperatorId(),
                    auditLog.getResourceType(),
                    auditLog.getResourceId(),
                    auditLog.getTimestamp(),
                    auditLog.getPreviousHash()
            );

            if (!recalculatedHash.equals(auditLog.getCurrentHash())) {
                tamperedIds.add(auditLog.getLogId());
                log.warn("Tampered log detected: {}", auditLog.getLogId());
            }

            expectedPreviousHash = auditLog.getCurrentHash();
        }

        AuditVerifyResult result = new AuditVerifyResult();
        result.setValid(tamperedIds.isEmpty());
        result.setTamperedLogIds(tamperedIds);
        result.setVerifiedCount(allLogs.size());
        result.setTamperedCount(tamperedIds.size());
        result.setMessage(tamperedIds.isEmpty() ? "完整哈希链验证通过" : "哈希链断裂或发现篡改");
        
        return result;
    }

    public boolean verifySingleLog(AuditLog auditLog) {
        String recalculatedHash = hashChainService.calculateHash(
                auditLog.getLogId(),
                auditLog.getOperation(),
                auditLog.getOperatorId(),
                auditLog.getResourceType(),
                auditLog.getResourceId(),
                auditLog.getTimestamp(),
                auditLog.getPreviousHash()
        );
        return recalculatedHash.equals(auditLog.getCurrentHash());
    }
}
