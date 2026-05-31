package com.solocoder.dns.mtls.model;

import lombok.Data;
import java.io.Serializable;

@Data
public class RotationPolicy implements Serializable {
    private String policyId;
    private String commonNamePattern;
    private Integer autoRotateDaysBeforeExpiry;
    private String keyAlgorithm;
    private Integer keySize;
    private String signatureAlgorithm;
    private Integer validityDays;
}
