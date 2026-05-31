package com.meshcontrol.mtls.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RotationPolicyRequest {

    @NotBlank(message = "bundleId is required")
    private String bundleId;

    private Integer rotationDays;
    private Boolean autoRotate = true;
    private Integer warningDays = 30;
}
