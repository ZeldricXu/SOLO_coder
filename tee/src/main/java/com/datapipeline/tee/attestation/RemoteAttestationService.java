package com.datapipeline.tee.attestation;

import com.datapipeline.tee.enclave.EnclaveInstance;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

@Slf4j
public class RemoteAttestationService {

    public enum AttestationResult {
        PASSED,
        FAILED,
        PENDING,
        TIMEOUT
    }

    private final Map<String, AttestationReport> reports = new ConcurrentHashMap<>();
    private final List<String> trustedMrEnclaves = new CopyOnWriteArrayList<>();
    private final List<String> trustedMrSigners = new CopyOnWriteArrayList<>();
    private final SecureRandom secureRandom = new SecureRandom();

    public void addTrustedMrEnclave(String mrenclave) {
        trustedMrEnclaves.add(mrenclave);
        log.info("Added trusted MRENCLAVE: {}", mrenclave);
    }

    public void addTrustedMrSigner(String mrsigner) {
        trustedMrSigners.add(mrsigner);
        log.info("Added trusted MRSIGNER: {}", mrsigner);
    }

    public AttestationRequest createChallenge(EnclaveInstance enclave) {
        byte[] nonce = new byte[32];
        secureRandom.nextBytes(nonce);

        return AttestationRequest.builder()
                .requestId(UUID.randomUUID().toString())
                .enclaveId(enclave.getEnclaveId())
                .nonce(Base64.getEncoder().encodeToString(nonce))
                .timestamp(Instant.now())
                .build();
    }

    public AttestationReport verifyAttestation(EnclaveInstance enclave, AttestationResponse response) {
        log.info("Verifying attestation for enclave: id={}", enclave.getEnclaveId());

        boolean passed = true;
        List<String> reasons = new ArrayList<>();

        if (!verifySignature(response, enclave.getPublicKey())) {
            passed = false;
            reasons.add("Invalid signature");
        }

        if (!trustedMrEnclaves.isEmpty() && !trustedMrEnclaves.contains(Base64.getEncoder().encodeToString(enclave.getMrenclave()))) {
            passed = false;
            reasons.add("Untrusted MRENCLAVE");
        }

        if (!trustedMrSigners.isEmpty() && !trustedMrSigners.contains(Base64.getEncoder().encodeToString(enclave.getMrsigner()))) {
            passed = false;
            reasons.add("Untrusted MRSIGNER");
        }

        if (enclave.getIsvSvn() < 1) {
            passed = false;
            reasons.add("ISVSVN too old");
        }

        AttestationReport report = AttestationReport.builder()
                .reportId(UUID.randomUUID().toString())
                .enclaveId(enclave.getEnclaveId())
                .result(passed ? AttestationResult.PASSED : AttestationResult.FAILED)
                .timestamp(Instant.now())
                .reasons(reasons)
                .mrenclave(Base64.getEncoder().encodeToString(enclave.getMrenclave()))
                .mrsigner(Base64.getEncoder().encodeToString(enclave.getMrsigner()))
                .isvProdId(enclave.getIsvProdId())
                .isvSvn(enclave.getIsvSvn())
                .build();

        reports.put(enclave.getEnclaveId(), report);

        if (passed) {
            enclave.setStatus(EnclaveInstance.Status.ATTESTED);
            enclave.setAttestationReport(report.getReportId());
            log.info("Attestation passed for enclave: id={}", enclave.getEnclaveId());
        } else {
            enclave.setStatus(EnclaveInstance.Status.ATTESTATION_FAILED);
            log.warn("Attestation failed for enclave: id={}, reasons={}", enclave.getEnclaveId(), reasons);
        }

        return report;
    }

    private boolean verifySignature(AttestationResponse response, PublicKey publicKey) {
        try {
            String data = response.getNonce() + response.getTimestamp().toEpochMilli();
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(publicKey);
            signature.update(data.getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(response.getSignature()));
        } catch (Exception e) {
            log.error("Signature verification failed", e);
            return false;
        }
    }

    public Optional<AttestationReport> getReport(String enclaveId) {
        return Optional.ofNullable(reports.get(enclaveId));
    }

}
