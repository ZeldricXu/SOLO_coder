package com.chaoslab.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mtls_certificate")
public class MtlsCertificate extends BaseEntity {

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
}
