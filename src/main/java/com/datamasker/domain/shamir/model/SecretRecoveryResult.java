package com.datamasker.domain.shamir.model;

import lombok.Data;

import java.math.BigInteger;
import java.time.LocalDateTime;

@Data
public class SecretRecoveryResult {
    private String secretId;
    private BigInteger recoveredSecret;
    private LocalDateTime recoveredAt;
    private int participantCount;

    public SecretRecoveryResult(String secretId, BigInteger recoveredSecret, LocalDateTime recoveredAt, int participantCount) {
        this.secretId = secretId;
        this.recoveredSecret = recoveredSecret;
        this.recoveredAt = recoveredAt;
        this.participantCount = participantCount;
    }
}
