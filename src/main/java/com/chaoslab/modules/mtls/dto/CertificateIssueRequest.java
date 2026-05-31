package com.chaoslab.modules.mtls.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class CertificateIssueRequest {

    @NotBlank(message = "通用名称不能为空")
    private String commonName;

    private List<String> subjectAlternativeNames;

    private String organization;

    private String organizationalUnit;

    private String country;

    private String rotationPolicyId;

    private Integer validityDays;
}
