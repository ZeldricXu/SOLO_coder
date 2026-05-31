package com.datamasker.domain.tee.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AttestationResult {

    private String enclaveId;

    private boolean verified;

    private String measurementHash;

    private String expectedHash;

    private String reportBody;

    private LocalDateTime timestamp;

    private boolean signatureValid;
}
