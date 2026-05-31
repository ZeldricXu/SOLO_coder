package com.datamasker.domain.tee.enclave;

import com.datamasker.domain.tee.model.AttestationResult;
import com.datamasker.domain.tee.model.EnclaveInstance;
import com.datamasker.domain.tee.model.SecureChannel;
import com.datamasker.infrastructure.config.TeeConfig;
import com.datamasker.infrastructure.crypto.CryptoUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class AttestationService {

    private final TeeConfig teeConfig;
    private final EnclaveManager enclaveManager;
    private final ConcurrentHashMap<String, String> signatureStore = new ConcurrentHashMap<>();

    private static final String ATTESTATION_KEY = Base64.getEncoder()
            .encodeToString("tee-attestation-key".getBytes(StandardCharsets.UTF_8));

    public AttestationResult performAttestation(String enclaveId, String expectedMeasurement) {
        EnclaveInstance enclave = enclaveManager.getEnclave(enclaveId);
        if (enclave == null) {
            throw new RuntimeException("Enclave not found: " + enclaveId);
        }

        enclave.setStatus("ATTESTING");
        enclave.setUpdatedAt(LocalDateTime.now());

        String reportBody = generateReport(enclaveId, enclave.getMeasurementHash());
        boolean measurementMatches = enclave.getMeasurementHash().equals(expectedMeasurement);

        AttestationResult result = new AttestationResult();
        result.setEnclaveId(enclaveId);
        result.setMeasurementHash(enclave.getMeasurementHash());
        result.setExpectedHash(expectedMeasurement);
        result.setReportBody(reportBody);
        result.setTimestamp(LocalDateTime.now());

        if (measurementMatches) {
            enclave.setStatus("ATTESTED");
            result.setVerified(true);
            result.setSignatureValid(true);
        } else {
            enclave.setStatus("ERROR");
            result.setVerified(false);
            result.setSignatureValid(false);
        }

        enclave.setAttestationReport(reportBody);
        enclave.setUpdatedAt(LocalDateTime.now());

        return result;
    }

    public boolean verifyAttestation(AttestationResult result) {
        try {
            String expectedSignature = signatureStore.get(result.getEnclaveId());
            if (expectedSignature == null) {
                return false;
            }
            boolean valid = CryptoUtils.verifyHmac(result.getReportBody(), ATTESTATION_KEY, expectedSignature);
            result.setSignatureValid(valid);
            return valid;
        } catch (Exception e) {
            return false;
        }
    }

    public String generateReport(String enclaveId, String measurementHash) {
        String nonce = UUID.randomUUID().toString();
        String timestamp = LocalDateTime.now().toString();
        String reportBody = "{\"enclaveId\":\"" + enclaveId
                + "\",\"measurement\":\"" + measurementHash
                + "\",\"timestamp\":\"" + timestamp
                + "\",\"nonce\":\"" + nonce + "\"}";
        try {
            String signature = CryptoUtils.hmacSha256(reportBody, ATTESTATION_KEY);
            signatureStore.put(enclaveId, signature);
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign attestation report", e);
        }
        return reportBody;
    }

    public SecureChannel establishSecureChannel(String enclaveId) {
        EnclaveInstance enclave = enclaveManager.getEnclave(enclaveId);
        if (enclave == null) {
            throw new RuntimeException("Enclave not found: " + enclaveId);
        }
        try {
            String sessionKey = CryptoUtils.generateAesKey();
            SecureChannel channel = new SecureChannel();
            channel.setChannelId(UUID.randomUUID().toString());
            channel.setEnclaveId(enclaveId);
            channel.setSessionKey(sessionKey);
            channel.setEstablishedAt(LocalDateTime.now());
            channel.setActive(true);
            return channel;
        } catch (Exception e) {
            throw new RuntimeException("Failed to establish secure channel", e);
        }
    }
}
