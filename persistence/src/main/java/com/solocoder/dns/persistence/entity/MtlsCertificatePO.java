package com.solocoder.dns.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("mtls_certificate")
public class MtlsCertificatePO {
    @TableId(type = IdType.INPUT)
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
