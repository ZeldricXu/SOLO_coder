package com.meshcontrol.mtls.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class CertificateRequest {

    @NotBlank(message = "commonName is required")
    private String commonName;

    private List<String> sans;
    private String certType = "server";
    private Integer validityDays = 365;
    private String signingCaId;
    private String keyAlgorithm = "RSA";
    private Integer keySize = 2048;
}
