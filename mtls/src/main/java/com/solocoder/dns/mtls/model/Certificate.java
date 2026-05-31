package com.solocoder.dns.mtls.model;

import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class Certificate implements Serializable {
    private String certId;
    private String commonName;
    private String serialNumber;
    private String certificate;
    private String privateKey;
    private String issuer;
    private LocalDateTime notBefore;
    private LocalDateTime notAfter;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime rotatedAt;
}
