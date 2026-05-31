package com.meshcontrol.mtls.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RevocationRequest {

    @NotBlank(message = "certId is required")
    private String certId;

    private String reason = "unspecified";
}
