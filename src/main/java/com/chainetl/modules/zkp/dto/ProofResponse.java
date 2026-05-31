package com.chainetl.modules.zkp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProofResponse {

    private String proofId;
    private String circuitId;
    private Boolean verificationResult;
    private Instant verifiedAt;
    private Instant createdAt;
    private String errorMessage;
    private Map<String, Object> publicInputs;
    private Map<String, Object> verificationKey;
}
