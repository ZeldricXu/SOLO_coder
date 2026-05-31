package com.chaoslab.modules.mtls.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CertificateResponse {

    private String certId;
    private String commonName;
    private String serialNumber;
    private String certificatePem;
    private String privateKeyPem;
    private String issuer;
    private LocalDateTime notBefore;
    private LocalDateTime notAfter;
    private String status;
    private String rotationPolicyId;
    private Boolean needsRotation;
    private LocalDateTime createdAt;
}
