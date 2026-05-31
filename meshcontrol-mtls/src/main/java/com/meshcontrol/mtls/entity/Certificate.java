package com.meshcontrol.mtls.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.meshcontrol.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "certificate", autoResultMap = true)
public class Certificate extends BaseEntity {

    private String certId;
    private String serialNumber;
    private String commonName;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> sans;

    private String certType;
    private String issuer;
    private LocalDateTime notBefore;
    private LocalDateTime notAfter;
    private String status;
    private String pemData;
    private String privateKeyPem;
    private String issuerCertId;
}
