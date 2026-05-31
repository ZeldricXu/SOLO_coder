package com.datamasker.domain.tee.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class EnclaveInstance {

    private String enclaveId;

    private String status;

    private String measurementHash;

    private String attestationReport;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
