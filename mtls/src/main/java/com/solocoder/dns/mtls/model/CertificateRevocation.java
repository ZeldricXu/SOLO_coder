package com.solocoder.dns.mtls.model;

import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class CertificateRevocation implements Serializable {
    private String crlId;
    private String serialNumber;
    private String reason;
    private LocalDateTime revokedAt;
    private LocalDateTime expiresAt;
}
