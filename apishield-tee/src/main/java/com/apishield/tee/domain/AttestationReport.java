package com.apishield.tee.domain;

import com.apishield.domain.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class AttestationReport extends BaseEntity {
    private String reportId;
    private String enclaveId;
    private String attestationType;
    private String quote;
    private Map<String, Object> quoteData;
    private boolean verified;
    private String verificationResult;
    private LocalDateTime verificationTime;
    private String verifier;
    private Map<String, Object> claims;
}
