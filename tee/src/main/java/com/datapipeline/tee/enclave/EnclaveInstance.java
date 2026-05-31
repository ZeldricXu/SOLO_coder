package com.datapipeline.tee.enclave;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.security.PublicKey;
import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnclaveInstance {

    public enum Status {
        CREATED,
        INITIALIZED,
        RUNNING,
        PAUSED,
        TERMINATED,
        ATTESTATION_PENDING,
        ATTESTED,
        ATTESTATION_FAILED
    }

    private String enclaveId;
    private String enclaveType;
    private Status status;
    private PublicKey publicKey;
    private byte[] mrenclave;
    private byte[] mrsigner;
    private int isvProdId;
    private int isvSvn;
    private String attestationReport;
    private Instant createdAt;
    private Instant startedAt;
    private Instant terminatedAt;
    private long memorySize;
    private int threadCount;
    private Map<String, Object> metadata;

}
