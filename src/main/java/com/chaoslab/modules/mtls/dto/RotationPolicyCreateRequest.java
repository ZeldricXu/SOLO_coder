package com.chaoslab.modules.mtls.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RotationPolicyCreateRequest {

    @NotBlank(message = "策略名称不能为空")
    private String name;

    private Integer validityDays = 365;

    private Integer rotationDays = 30;

    private Boolean autoRotate = true;

    private String keyAlgorithm = "RSA";

    private Integer keySize = 2048;

    private String signatureAlgorithm = "SHA256withRSA";

    private Boolean enabled = true;
}
