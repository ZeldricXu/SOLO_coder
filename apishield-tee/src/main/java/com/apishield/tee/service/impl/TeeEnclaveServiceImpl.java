package com.apishield.tee.service.impl;

import com.apishield.common.exception.BusinessException;
import com.apishield.common.util.CryptoUtil;
import com.apishield.common.util.IdGenerator;
import com.apishield.tee.domain.AttestationReport;
import com.apishield.tee.domain.TeeEnclave;
import com.apishield.tee.dto.AttestationRequest;
import com.apishield.tee.dto.EnclaveCreateRequest;
import com.apishield.tee.dto.EncryptRequest;
import com.apishield.tee.service.TeeEnclaveService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TeeEnclaveServiceImpl implements TeeEnclaveService {

    private final Map<String, TeeEnclave> enclaveStore = new ConcurrentHashMap<>();
    private final Map<String, List<AttestationReport>> reportStore = new ConcurrentHashMap<>();

    @Override
    public TeeEnclave createEnclave(EnclaveCreateRequest request) {
        TeeEnclave enclave = new TeeEnclave();
        enclave.setId(IdGenerator.generateId("enclave"));
        enclave.setEnclaveId(enclave.getId());
        enclave.setEnclaveName(request.getEnclaveName());
        enclave.setEnclaveType(request.getEnclaveType() != null ? request.getEnclaveType() : "SGX");
        enclave.setHostId(request.getHostId());
        enclave.setHostAddress(request.getHostAddress());
        enclave.setPort(request.getPort());
        enclave.setStatus(TeeEnclave.EnclaveStatus.CREATED);
        enclave.setMrenclave(IdGenerator.generateId());
        enclave.setMrsigner(IdGenerator.generateId());
        enclave.setPublicKey(CryptoUtil.sha256(IdGenerator.generateId()));
        enclave.setCreatedAt(LocalDateTime.now());
        enclave.setUpdatedAt(LocalDateTime.now());

        if (request.getAttributes() != null) {
            enclave.getAttributes().putAll(request.getAttributes());
        }

        enclaveStore.put(enclave.getEnclaveId(), enclave);
        reportStore.put(enclave.getEnclaveId(), new ArrayList<>());

        log.info("Created TEE enclave: {}, type: {}, host: {}", 
                enclave.getEnclaveId(), enclave.getEnclaveType(), request.getHostAddress());
        return enclave;
    }

    @Override
    public TeeEnclave getEnclave(String enclaveId) {
        TeeEnclave enclave = enclaveStore.get(enclaveId);
        if (enclave == null) {
            throw new BusinessException("NOT_FOUND", "TEE Enclave不存在: " + enclaveId);
        }
        return enclave;
    }

    @Override
    public List<TeeEnclave> getAllEnclaves() {
        return new ArrayList<>(enclaveStore.values());
    }

    @Override
    public List<TeeEnclave> getEnclavesByStatus(TeeEnclave.EnclaveStatus status) {
        return enclaveStore.values().stream()
                .filter(e -> e.getStatus() == status)
                .collect(Collectors.toList());
    }

    @Override
    public TeeEnclave startEnclave(String enclaveId) {
        TeeEnclave enclave = getEnclave(enclaveId);
        if (enclave.getStatus() != TeeEnclave.EnclaveStatus.CREATED && 
            enclave.getStatus() != TeeEnclave.EnclaveStatus.STOPPED) {
            throw new BusinessException("TEE_001", "Enclave状态不允许启动: " + enclave.getStatus());
        }

        enclave.setStatus(TeeEnclave.EnclaveStatus.INITIALIZING);
        enclave.setUpdatedAt(LocalDateTime.now());

        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        enclave.setStatus(TeeEnclave.EnclaveStatus.READY);
        enclave.setUpdatedAt(LocalDateTime.now());

        log.info("Started TEE enclave: {}", enclaveId);
        return enclave;
    }

    @Override
    public TeeEnclave stopEnclave(String enclaveId) {
        TeeEnclave enclave = getEnclave(enclaveId);
        enclave.setStatus(TeeEnclave.EnclaveStatus.SUSPENDED);
        enclave.setUpdatedAt(LocalDateTime.now());
        log.info("Stopped TEE enclave: {}", enclaveId);
        return enclave;
    }

    @Override
    public TeeEnclave restartEnclave(String enclaveId) {
        stopEnclave(enclaveId);
        return startEnclave(enclaveId);
    }

    @Override
    public void terminateEnclave(String enclaveId) {
        TeeEnclave enclave = getEnclave(enclaveId);
        enclave.setStatus(TeeEnclave.EnclaveStatus.TERMINATED);
        enclave.setUpdatedAt(LocalDateTime.now());
        log.info("Terminated TEE enclave: {}", enclaveId);
    }

    @Override
    public AttestationReport performAttestation(AttestationRequest request) {
        TeeEnclave enclave = getEnclave(request.getEnclaveId());
        if (enclave.getStatus() != TeeEnclave.EnclaveStatus.READY && 
            enclave.getStatus() != TeeEnclave.EnclaveStatus.RUNNING) {
            throw new BusinessException("TEE_002", "Enclave未准备好进行认证");
        }

        AttestationReport report = new AttestationReport();
        report.setId(IdGenerator.generateId("attest"));
        report.setReportId(report.getId());
        report.setEnclaveId(request.getEnclaveId());
        report.setAttestationType(request.getAttestationType() != null ? request.getAttestationType() : "REMOTE");
        report.setQuote(CryptoUtil.sha256(request.getChallenge() != null ? request.getChallenge() : IdGenerator.generateId()));
        report.setVerified(true);
        report.setVerificationResult("PASSED");
        report.setVerificationTime(LocalDateTime.now());
        report.setVerifier("APIShield_TEE_Verifier");
        report.setCreatedAt(LocalDateTime.now());
        report.setUpdatedAt(LocalDateTime.now());

        Map<String, Object> claims = new HashMap<>();
        claims.put("mrenclave", enclave.getMrenclave());
        claims.put("mrsigner", enclave.getMrsigner());
        claims.put("enclaveType", enclave.getEnclaveType());
        claims.put("timestamp", System.currentTimeMillis());
        report.setClaims(claims);

        reportStore.get(request.getEnclaveId()).add(report);
        
        enclave.setLastAttestationTime(LocalDateTime.now());
        enclave.setAttestationStatus("VERIFIED");
        enclave.setStatus(TeeEnclave.EnclaveStatus.RUNNING);
        enclave.setUpdatedAt(LocalDateTime.now());

        log.info("Performed attestation for enclave: {}, result: {}", request.getEnclaveId(), report.isVerified());
        return report;
    }

    @Override
    public AttestationReport getAttestationReport(String reportId) {
        return reportStore.values().stream()
                .flatMap(List::stream)
                .filter(r -> reportId.equals(r.getReportId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException("NOT_FOUND", "认证报告不存在: " + reportId));
    }

    @Override
    public List<AttestationReport> getAttestationReports(String enclaveId) {
        getEnclave(enclaveId);
        return reportStore.getOrDefault(enclaveId, Collections.emptyList());
    }

    @Override
    public boolean verifyAttestation(String reportId) {
        AttestationReport report = getAttestationReport(reportId);
        return report.isVerified();
    }

    @Override
    public String encryptInEnclave(EncryptRequest request) {
        TeeEnclave enclave = getEnclave(request.getEnclaveId());
        if (enclave.getStatus() != TeeEnclave.EnclaveStatus.RUNNING) {
            throw new BusinessException("TEE_001", "Enclave未运行，无法加密");
        }
        String encrypted = CryptoUtil.encrypt(request.getPlainData());
        log.info("Encrypted data in enclave: {}", request.getEnclaveId());
        return encrypted;
    }

    @Override
    public String decryptInEnclave(String enclaveId, String encryptedData, String keyId) {
        TeeEnclave enclave = getEnclave(enclaveId);
        if (enclave.getStatus() != TeeEnclave.EnclaveStatus.RUNNING) {
            throw new BusinessException("TEE_001", "Enclave未运行，无法解密");
        }
        String decrypted = CryptoUtil.decrypt(encryptedData);
        log.info("Decrypted data in enclave: {}", enclaveId);
        return decrypted;
    }

    @Override
    public Map<String, Object> executeSecureFunction(String enclaveId, String functionName, Map<String, Object> params) {
        TeeEnclave enclave = getEnclave(enclaveId);
        if (enclave.getStatus() != TeeEnclave.EnclaveStatus.RUNNING) {
            throw new BusinessException("TEE_001", "Enclave未运行，无法执行安全函数");
        }

        Map<String, Object> result = new HashMap<>();
        result.put("function", functionName);
        result.put("executedInEnclave", true);
        result.put("enclaveId", enclaveId);
        result.put("timestamp", System.currentTimeMillis());
        result.put("status", "SUCCESS");

        log.info("Executed secure function {} in enclave: {}", functionName, enclaveId);
        return result;
    }

    @Override
    public TeeEnclave healthCheck(String enclaveId) {
        TeeEnclave enclave = getEnclave(enclaveId);
        enclave.setLastHealthCheckTime(LocalDateTime.now());
        enclave.setUpdatedAt(LocalDateTime.now());
        log.debug("Health check performed for enclave: {}", enclaveId);
        return enclave;
    }
}
