package com.apishield.tee.service;

import com.apishield.application.service.ApplicationService;
import com.apishield.tee.domain.AttestationReport;
import com.apishield.tee.domain.TeeEnclave;
import com.apishield.tee.dto.AttestationRequest;
import com.apishield.tee.dto.EnclaveCreateRequest;
import com.apishield.tee.dto.EncryptRequest;
import java.util.List;
import java.util.Map;

public interface TeeEnclaveService extends ApplicationService {
    TeeEnclave createEnclave(EnclaveCreateRequest request);
    TeeEnclave getEnclave(String enclaveId);
    List<TeeEnclave> getAllEnclaves();
    List<TeeEnclave> getEnclavesByStatus(TeeEnclave.EnclaveStatus status);
    TeeEnclave startEnclave(String enclaveId);
    TeeEnclave stopEnclave(String enclaveId);
    TeeEnclave restartEnclave(String enclaveId);
    void terminateEnclave(String enclaveId);
    
    AttestationReport performAttestation(AttestationRequest request);
    AttestationReport getAttestationReport(String reportId);
    List<AttestationReport> getAttestationReports(String enclaveId);
    boolean verifyAttestation(String reportId);
    
    String encryptInEnclave(EncryptRequest request);
    String decryptInEnclave(String enclaveId, String encryptedData, String keyId);
    Map<String, Object> executeSecureFunction(String enclaveId, String functionName, Map<String, Object> params);
    
    TeeEnclave healthCheck(String enclaveId);
}
