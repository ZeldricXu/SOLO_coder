package com.meshcontrol.mtls.entity;

import com.meshcontrol.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
public class CertificateRevocation extends BaseEntity {

    private String revocationId;
    private String certId;
    private String serialNumber;
    private String reason;
    private LocalDateTime revokedAt;
    private String crlEntry;
}
