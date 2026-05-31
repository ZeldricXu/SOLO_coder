package com.chain.infrastructure.zkverifier.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ZkProofResult {

    private String proofId;

    private String circuitId;

    private String schemeType;

    private Boolean verified;

    private String verificationResult;

    private Long verificationTimeMs;

    private LocalDateTime verifiedAt;
}
