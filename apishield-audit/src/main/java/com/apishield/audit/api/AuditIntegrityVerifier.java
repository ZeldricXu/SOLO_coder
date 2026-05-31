package com.apishield.audit.api;

import com.apishield.audit.domain.model.AuditVerifyResult;
import java.util.List;

public interface AuditIntegrityVerifier {
    AuditVerifyResult verifyLogIntegrity(List<String> logIds);
    AuditVerifyResult verifyBlockRange(int startHeight, int endHeight);
    AuditVerifyResult verifyFullChain();
    boolean verifySingleLog(String logId);
}
