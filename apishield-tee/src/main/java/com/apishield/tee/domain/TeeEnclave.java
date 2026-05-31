package com.apishield.tee.domain;

import com.apishield.domain.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class TeeEnclave extends BaseEntity {
    private String enclaveId;
    private String enclaveName;
    private String enclaveType;
    private EnclaveStatus status;
    private String hostId;
    private String hostAddress;
    private int port;
    private String mrenclave;
    private String mrsigner;
    private String publicKey;
    private Map<String, Object> attributes;
    private LocalDateTime lastAttestationTime;
    private LocalDateTime lastHealthCheckTime;
    private String attestationStatus;

    public TeeEnclave() {
        this.attributes = new HashMap<>();
    }

    public enum EnclaveStatus {
        CREATED, INITIALIZING, READY, RUNNING, SUSPENDED, TERMINATED, ERROR
    }
}
