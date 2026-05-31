package com.apishield.security.keysharding.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecoveryResult {
    private boolean success;
    private String recoveredSecret;
    private String keyId;
    private int usedShares;
    private int threshold;
    private String message;
}
